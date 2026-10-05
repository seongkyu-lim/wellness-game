package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordAuthService {
    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptGuard attemptGuard;
    /** 없는 아이디도 해시 비교 한 번을 거치게 해 응답 시간으로 아이디 존재가 드러나지 않게 한다(#55). */
    private final String dummyPasswordHash;

    static final int DEFAULT_MAX_KEYS = 100_000;

    /** 기본 잠금 설정(아이디·IP 5회, 아이디 전체 50회 → 15분)과 시스템 시계를 쓴다. */
    public PasswordAuthService(UserCredentialRepository repository, PasswordEncoder passwordEncoder) {
        this(repository, passwordEncoder, LoginLockoutProperties.DEFAULTS, Clock.systemUTC(), DEFAULT_MAX_KEYS);
    }

    /**
     * @param maxKeys 실패 기록을 메모리에 둘 최대 키 수. 레이트 리밋과 같은 설정({@code app.rate-limit.max-keys})을 쓴다.
     */
    @Autowired
    public PasswordAuthService(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            LoginLockoutProperties lockoutProperties,
            Clock clock,
            @Value("${app.rate-limit.max-keys:" + DEFAULT_MAX_KEYS + "}") int maxKeys
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.attemptGuard = new LoginAttemptGuard(lockoutProperties, clock, maxKeys);
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public PasswordAuthResult signUp(String username, String rawPassword, String displayName) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (repository.existsByUsername(normalizedUsername)) {
            throw new BadRequestException(Messages.get("auth.username-taken"));
        }

        String resolvedDisplayName = displayName == null || displayName.isBlank()
                ? normalizedUsername
                : displayName.trim();
        UserCredential credential = new UserCredential(
                normalizedUsername,
                passwordEncoder.encode(rawPassword),
                UUID.randomUUID().toString(),
                resolvedDisplayName
        );
        UserCredential saved = repository.save(credential);
        return new PasswordAuthResult(saved.getUserIdentifier(), saved.getDisplayName());
    }

    /** 클라이언트 IP 를 모르는 호출(테스트 등)용. 모든 시도를 같은 IP 로 본다. */
    @Transactional(readOnly = true)
    public PasswordAuthResult signIn(String username, String rawPassword) {
        return signIn(username, rawPassword, "");
    }

    /**
     * 아이디·비밀번호 로그인. 실패 사유(없는 아이디, 틀린 비밀번호, 잠금)와 상관없이 같은 400 문구로 응답하고,
     * 모든 경로에서 bcrypt 비교를 한 번 해 응답 시간도 비슷하게 맞춘다.
     *
     * <p>비교 전에 시도를 먼저 실패로 예약해 동시 요청이 잠금 한도를 넘지 못하게 하고, 성공하면 예약을 되돌린다
     * ({@link LoginAttemptGuard}). 잠긴 동안은 올바른 비밀번호도 거부한다.
     */
    @Transactional(readOnly = true)
    public PasswordAuthResult signIn(String username, String rawPassword, String clientIp) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (!attemptGuard.tryReserve(normalizedUsername, clientIp)) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw invalidCredentials();
        }

        Optional<UserCredential> found = repository.findByUsername(normalizedUsername);
        String hash = found.map(UserCredential::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !matches) {
            throw invalidCredentials();
        }

        attemptGuard.recordSuccess(normalizedUsername, clientIp);
        UserCredential credential = found.get();
        return new PasswordAuthResult(credential.getUserIdentifier(), credential.getDisplayName());
    }

    private static BadRequestException invalidCredentials() {
        return new BadRequestException(Messages.get("auth.invalid-credentials"));
    }
}
