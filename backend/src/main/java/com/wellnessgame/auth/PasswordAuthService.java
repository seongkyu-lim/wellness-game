package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 기본 잠금 설정(5회 실패 → 15분)과 시스템 시계를 쓴다. */
    public PasswordAuthService(UserCredentialRepository repository, PasswordEncoder passwordEncoder) {
        this(repository, passwordEncoder, LoginLockoutProperties.DEFAULTS, Clock.systemUTC());
    }

    @Autowired
    public PasswordAuthService(
            UserCredentialRepository repository,
            PasswordEncoder passwordEncoder,
            LoginLockoutProperties lockoutProperties,
            Clock clock
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.attemptGuard = new LoginAttemptGuard(lockoutProperties, clock);
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

    /**
     * 아이디·비밀번호 로그인. 실패 사유(없는 아이디, 틀린 비밀번호, 잠금)와 상관없이 같은 400 문구로 응답하고,
     * 모든 경로에서 bcrypt 비교를 한 번 해 응답 시간도 비슷하게 맞춘다. 연속 실패가 한도에 닿으면 잠금 시간 동안
     * 올바른 비밀번호도 거부하며, 성공하면 실패 횟수를 지운다.
     */
    @Transactional(readOnly = true)
    public PasswordAuthResult signIn(String username, String rawPassword) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (attemptGuard.isLocked(normalizedUsername)) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw invalidCredentials();
        }

        Optional<UserCredential> found = repository.findByUsername(normalizedUsername);
        String hash = found.map(UserCredential::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !matches) {
            attemptGuard.recordFailure(normalizedUsername);
            throw invalidCredentials();
        }

        attemptGuard.recordSuccess(normalizedUsername);
        UserCredential credential = found.get();
        return new PasswordAuthResult(credential.getUserIdentifier(), credential.getDisplayName());
    }

    private static BadRequestException invalidCredentials() {
        return new BadRequestException(Messages.get("auth.invalid-credentials"));
    }
}
