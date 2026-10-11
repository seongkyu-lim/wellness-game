package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 회전 리프레시 토큰(Auth v2, #55).
 *
 * <ul>
 *   <li>원문은 32바이트 SecureRandom 의 base64url(패딩 없음)이고, DB 에는 SHA-256 hex 만 저장한다.</li>
 *   <li>로그인마다 새 family 를 만든다. 회전하면 기존 토큰을 폐기하고 replaced_by 를 남긴 뒤 같은 family 로 새 토큰을 준다.</li>
 *   <li>이미 폐기된 토큰이 다시 오면 탈취 후 재사용으로 보고 family 전체를 폐기한 뒤 401 이다.
 *       같은 토큰으로 동시에 회전해도 조건부 update 로 하나만 성공하고 나머지는 재사용으로 처리된다.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, RefreshTokenProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 새 로그인: 새 family 의 첫 토큰. */
    @Transactional
    public IssuedRefreshToken issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("토큰 주체(userId)가 비어 있습니다.");
        }
        return create(userId, UUID.randomUUID(), clock.instant()).issued();
    }

    /**
     * 리프레시 토큰을 회전한다.
     *
     * @throws UnauthorizedException 없음·만료·재사용(이때 family 전체 폐기는 커밋된다)
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = find(rawToken);
        if (current == null) {
            throw new UnauthorizedException(Messages.get("auth.refresh.invalid"));
        }
        if (current.getRevokedAt() != null) {
            throw reuseDetected(current, now);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new UnauthorizedException(Messages.get("auth.refresh.expired"));
        }
        if (repository.revokeIfActive(current.getId(), now) == 0) {
            throw reuseDetected(current, now); // 동시 회전에서 진 요청
        }
        Created next = create(current.getUserId(), current.getFamilyId(), now);
        repository.markReplaced(current.getId(), next.id());
        return new Rotation(current.getUserId(), next.issued());
    }

    /** 로그아웃: 이 사용자의 토큰이면 그 family 를 폐기한다. 없거나 남의 토큰이면 아무것도 하지 않는다. */
    @Transactional
    public void revokeFamily(String userId, String rawToken) {
        RefreshToken token = find(rawToken);
        if (token != null && token.getUserId().equals(userId)) {
            repository.revokeFamily(token.getFamilyId(), clock.instant());
        }
    }

    /** 모든 기기 로그아웃. */
    @Transactional
    public int revokeAll(String userId) {
        return repository.revokeAllForUser(userId, clock.instant());
    }

    /** 만료된 행과, 폐기된 지 revoked-retention 이 지난 행을 지운다. 지운 행 수. */
    @Transactional
    public int purgeExpiredAndRevoked() {
        Instant now = clock.instant();
        return repository.deleteExpiredOrRevokedBefore(now, now.minus(properties.revokedRetention()));
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }

    private RefreshToken find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return repository.findByTokenHash(Sha256.hex(rawToken.trim())).orElse(null);
    }

    private UnauthorizedException reuseDetected(RefreshToken token, Instant now) {
        int revoked = repository.revokeFamily(token.getFamilyId(), now);
        log.warn("리프레시 토큰 재사용 감지: userId={}, family={}, 폐기 {}건", token.getUserId(), token.getFamilyId(), revoked);
        return new UnauthorizedException(Messages.get("auth.refresh.reused"));
    }

    private Created create(String userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken saved = repository.save(
                new RefreshToken(userId, familyId, Sha256.hex(raw), now, now.plus(properties.ttl())));
        return new Created(saved.getId(), new IssuedRefreshToken(raw, properties.ttl().toSeconds()));
    }

    /** 회전 결과: 토큰 주체와 새 리프레시 토큰. */
    public record Rotation(String userId, IssuedRefreshToken refreshToken) {
    }

    private record Created(UUID id, IssuedRefreshToken issued) {
    }
}
