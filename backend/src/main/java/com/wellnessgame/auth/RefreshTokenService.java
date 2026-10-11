package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 회전 리프레시 토큰(Auth v2, #55).
 *
 * <ul>
 *   <li>원문은 32바이트 SecureRandom 의 base64url(패딩 없음)이고, DB 에는 SHA-256 hex 만 저장한다.</li>
 *   <li>로그인마다 새 family 를 만든다. 회전하면 기존 토큰을 폐기하고 replaced_by 를 남긴 뒤 같은 family 로 새 토큰을 준다.
 *       만료는 min(지금 + ttl, family 생성 + absolute-ttl) 이다.</li>
 *   <li>회전된 지 reuse-grace 안의 토큰이 다시 오면(응답 유실·동시 요청) 같은 family 에 형제 토큰을 하나 더 준다.
 *       기존 후계 토큰도 계속 유효하다. 같은 토큰의 동시 회전은 행 잠금으로 직렬화돼 둘째 요청이 이 경로를 탄다.</li>
 *   <li>유예 구간이 지난 회전된 토큰이 오면 탈취 후 재사용으로 보고 family 전체를 폐기하고, 그 사용자의 기존 액세스 토큰도
 *       무효로 만든 뒤 401 이다. 로그아웃으로 폐기된 토큰은 그냥 401 이다.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final TokenValidityService tokenValidityService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository repository,
            RefreshTokenProperties properties,
            TokenValidityService tokenValidityService,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.tokenValidityService = tokenValidityService;
        this.clock = clock;
    }

    /** 새 로그인: 새 family 의 첫 토큰. */
    @Transactional
    public IssuedRefreshToken issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("토큰 주체(userId)가 비어 있습니다.");
        }
        Instant now = clock.instant();
        return create(userId, UUID.randomUUID(), now, now).issued();
    }

    /**
     * 리프레시 토큰을 회전한다.
     *
     * @throws UnauthorizedException 없음·만료·폐기·재사용(재사용 처리 결과는 커밋된다)
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = rawToken == null || rawToken.isBlank() ? null
                : repository.findForUpdateByTokenHash(Sha256.hex(rawToken.trim())).orElse(null);
        if (current == null) {
            throw invalid();
        }
        if (current.getRevokedAt() != null) {
            return rotateRevoked(current, now);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new UnauthorizedException(Messages.get("auth.refresh.expired"));
        }
        Created next = create(current.getUserId(), current.getFamilyId(), current.getFamilyCreatedAt(), now);
        current.markRotated(now, next.id());
        return new Rotation(current.getUserId(), next.issued());
    }

    private Rotation rotateRevoked(RefreshToken token, Instant now) {
        if (token.getReplacedBy() == null) {
            throw invalid(); // 로그아웃·재사용 감지로 폐기됨
        }
        boolean withinGrace = !now.isAfter(token.getRevokedAt().plus(properties.reuseGrace()));
        if (!withinGrace) {
            throw reuseDetected(token, now);
        }
        if (!repository.existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(token.getFamilyId(), now)) {
            throw invalid(); // 유예 구간 안이지만 family 가 이미 로그아웃됨
        }
        Created sibling = create(token.getUserId(), token.getFamilyId(), token.getFamilyCreatedAt(), now);
        return new Rotation(token.getUserId(), sibling.issued());
    }

    /** 로그아웃(토큰 소유가 곧 권한): 그 토큰의 family 를 폐기한다. 없는 토큰이면 아무것도 하지 않는다. */
    @Transactional
    public void revokeFamily(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(Sha256.hex(rawToken.trim()))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** 모든 기기 로그아웃: 리프레시 토큰을 모두 폐기하고 이미 발급된 액세스 토큰도 무효로 만든다. */
    @Transactional
    public void revokeAll(String userId) {
        repository.revokeAllForUser(userId, clock.instant());
        tokenValidityService.revokeIssuedTokens(userId);
    }

    /** 만료된 행과, 폐기된 지 revoked-retention 이 지난 행을 지운다. 지운 행 수. */
    @Transactional
    public int purgeExpiredAndRevoked() {
        Instant now = clock.instant();
        return repository.deleteExpiredOrRevokedBefore(now, now.minus(properties.revokedRetention()));
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException(Messages.get("auth.refresh.invalid"));
    }

    private UnauthorizedException reuseDetected(RefreshToken token, Instant now) {
        int revoked = repository.revokeFamily(token.getFamilyId(), now);
        tokenValidityService.revokeIssuedTokens(token.getUserId());
        log.warn("리프레시 토큰 재사용 감지: userId={}, family={}, 폐기 {}건", token.getUserId(), token.getFamilyId(), revoked);
        return new UnauthorizedException(Messages.get("auth.refresh.reused"));
    }

    private Created create(String userId, UUID familyId, Instant familyCreatedAt, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = earliest(now.plus(properties.ttl()), familyCreatedAt.plus(properties.absoluteTtl()));
        RefreshToken saved = repository.save(
                new RefreshToken(userId, familyId, familyCreatedAt, Sha256.hex(raw), now, expiresAt));
        return new Created(saved.getId(), new IssuedRefreshToken(raw, Duration.between(now, expiresAt).toSeconds()));
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** 회전 결과: 토큰 주체와 새 리프레시 토큰. */
    public record Rotation(String userId, IssuedRefreshToken refreshToken) {
    }

    private record Created(UUID id, IssuedRefreshToken issued) {
    }
}
