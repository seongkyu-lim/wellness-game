package com.wellnessgame.auth;

import com.wellnessgame.support.MutableClock;
import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 리프레시 토큰 서비스의 시간 경계(만료·유예·절대 수명)와 입력 처리. 기본 설정: ttl 30일, 절대 90일, 유예 30초, 읽기 7일/30일. */
@SpringBootTest
@Import(TestClockConfig.class)
class RefreshTokenServiceTest {
    private static final Instant T0 = TestClockConfig.DEFAULT_NOW;

    @Autowired
    private RefreshTokenService service;
    @Autowired
    private RefreshTokenRepository repository;
    @Autowired
    private UserAuthStateRepository authStateRepository;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        clock.setInstant(T0);
        repository.deleteAll();
        authStateRepository.deleteAll();
    }

    @Test
    void issueRejectsBlankUser() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.issue(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.issue("  "));
    }

    @Test
    void issuedTokenExpiresInConfiguredSecondsAndDefaultsToFullScope() {
        IssuedRefreshToken issued = service.issue("u-issue");

        assertThat(issued.expiresIn()).isEqualTo(Duration.ofDays(30).toSeconds());
        assertThat(repository.findByUserId("u-issue")).singleElement()
                .satisfies(row -> assertThat(row.getScope()).isEqualTo(TokenScope.FULL));
    }

    @Test
    void readScopeFamilyHasShorterLifetime() {
        IssuedRefreshToken issued = service.issue("u-read", TokenScope.READ);

        assertThat(issued.expiresIn()).isEqualTo(Duration.ofDays(7).toSeconds());
    }

    @Test
    void rotateTrimsSurroundingWhitespaceOfPresentedToken() {
        String raw = service.issue("u-trim").refreshToken();

        assertThat(service.rotate("  " + raw + "\n").userId()).isEqualTo("u-trim");
    }

    @Test
    void rotateRejectsNullBlankAndUnknownTokens() {
        for (String token : new String[]{null, "", "   ", "unknown-token"}) {
            assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void tokenIsStillAcceptedOneMillisecondBeforeExpiry() {
        String raw = service.issue("u-exp").refreshToken();

        clock.setInstant(T0.plus(Duration.ofDays(30)).minusMillis(1));

        assertThatCode(() -> service.rotate(raw)).doesNotThrowAnyException();
    }

    @Test
    void expiryBoundaryIsExclusive() {
        String raw = service.issue("u-exp-edge").refreshToken();

        clock.setInstant(T0.plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("리프레시 토큰이 만료되었습니다. 다시 로그인해 주세요.");
    }

    @Test
    void rotatedTokenNeverOutlivesFamilyAbsoluteLifetime() {
        String raw = service.issue("u-abs").refreshToken();
        // 25일마다 회전: 마지막 회전 시점(75일)에는 idle 30일이 절대 수명(90일)을 넘으므로 90일로 잘린다
        for (int day = 25; day <= 75; day += 25) {
            clock.setInstant(T0.plus(Duration.ofDays(day)));
            raw = service.rotate(raw).refreshToken().refreshToken();
        }

        RefreshToken last = repository.findByUserId("u-abs").stream()
                .filter(row -> row.getRevokedAt() == null).findFirst().orElseThrow();
        assertThat(last.getExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(90)));
    }

    @Test
    void graceWindowIsInclusiveAtExactlyThirtySeconds() {
        String first = service.issue("u-grace").refreshToken();
        service.rotate(first);

        clock.setInstant(T0.plusSeconds(30));
        assertThat(service.rotate(first).userId()).isEqualTo("u-grace");
    }

    @Test
    void graceReissueHappensOncePerRotatedToken() {
        String first = service.issue("u-once").refreshToken();
        service.rotate(first);
        service.rotate(first);

        assertThat(repository.findByUserId("u-once").stream().filter(RefreshToken::isGraceReissued)).hasSize(1);
        assertThatThrownBy(() -> service.rotate(first))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이미 사용된 리프레시 토큰입니다. 보안을 위해 다시 로그인해 주세요.");
        assertThat(repository.findByUserId("u-once")).isNotEmpty().allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
    }

    @Test
    void reuseDetectionRevokesOnlyTheAffectedFamilyOfTheSameUser() {
        String stolen = service.issue("u-fam").refreshToken();
        String otherDevice = service.issue("u-fam").refreshToken();
        service.rotate(stolen);
        clock.setInstant(T0.plusSeconds(31));

        assertThatThrownBy(() -> service.rotate(stolen)).isInstanceOf(UnauthorizedException.class);

        assertThat(service.rotate(otherDevice).userId()).isEqualTo("u-fam");
    }

    @Test
    void reuseDetectionInvalidatesAccessTokensIssuedBefore() {
        String stolen = service.issue("u-validity").refreshToken();
        service.rotate(stolen);
        clock.setInstant(T0.plusSeconds(31));

        assertThatThrownBy(() -> service.rotate(stolen)).isInstanceOf(UnauthorizedException.class);

        assertThat(authStateRepository.findById("u-validity")).hasValueSatisfying(state ->
                assertThat(state.getTokensValidAfter()).isEqualTo(T0.plusSeconds(31)));
    }

    @Test
    void revokeFamilyIgnoresBlankOrUnknownTokenAndRevokesTheRest() {
        String raw = service.issue("u-rev").refreshToken();

        assertThatCode(() -> {
            service.revokeFamily(null);
            service.revokeFamily(" ");
            service.revokeFamily("unknown");
        }).doesNotThrowAnyException();
        assertThat(repository.findByUserId("u-rev")).isNotEmpty().allSatisfy(row -> assertThat(row.getRevokedAt()).isNull());

        service.revokeFamily(raw);
        assertThat(repository.findByUserId("u-rev")).isNotEmpty().allSatisfy(row -> assertThat(row.getRevokedAt()).isEqualTo(T0));
        // 폐기 뒤 회전은 재사용 처리 없이 그냥 거부(replaced_by 없음)
        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("리프레시 토큰이 유효하지 않습니다. 다시 로그인해 주세요.");
    }

    @Test
    void revokeFamilyOfFullScopeCutsReadTokensButReadFamilyDoesNot() {
        String full = service.issue("u-scope", TokenScope.FULL).refreshToken();
        String read = service.issue("u-scope", TokenScope.READ).refreshToken();

        service.revokeFamily(read);
        assertThat(authStateRepository.findById("u-scope")).isEmpty();

        service.revokeFamily(full);
        assertThat(authStateRepository.findById("u-scope")).hasValueSatisfying(state ->
                assertThat(state.getReadTokensValidAfter()).isEqualTo(T0));
    }

    @Test
    void revokeAllRevokesEveryFamilyOfTheUserOnly() {
        List<String> mine = List.of(service.issue("u-all").refreshToken(), service.issue("u-all").refreshToken());
        String others = service.issue("u-other").refreshToken();

        service.revokeAll("u-all");

        for (String token : mine) {
            assertThatThrownBy(() -> service.rotate(token)).isInstanceOf(UnauthorizedException.class);
        }
        assertThat(service.rotate(others).userId()).isEqualTo("u-other");
        assertThat(authStateRepository.findById("u-all")).hasValueSatisfying(state ->
                assertThat(state.getTokensValidAfter()).isEqualTo(T0));
    }

    @Test
    void purgeRemovesExpiredAndOldRevokedRowsAtExactBoundaries() {
        String revoked = service.issue("u-purge-1").refreshToken();
        service.revokeFamily(revoked);
        service.issue("u-purge-2");

        // 폐기 7일 경과 직전: 남음
        clock.setInstant(T0.plus(Duration.ofDays(7)).minusMillis(1));
        assertThat(service.purgeExpiredAndRevoked()).isZero();
        // 정확히 7일: 폐기 행 삭제
        clock.setInstant(T0.plus(Duration.ofDays(7)));
        assertThat(service.purgeExpiredAndRevoked()).isEqualTo(1);
        // 만료 시각(30일): 삭제
        clock.setInstant(T0.plus(Duration.ofDays(30)));
        assertThat(service.purgeExpiredAndRevoked()).isEqualTo(1);
        assertThat(repository.count()).isZero();
    }
}
