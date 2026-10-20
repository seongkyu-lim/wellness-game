package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 아이디·비밀번호 가입과 로그인.
 *
 * <p>아이디는 앞뒤 공백을 지우고 소문자로 맞춰 저장·조회한다(#106). 그 전에 대소문자를 섞어 가입한 계정도 로그인되도록
 * 정확히 일치하는 행이 없으면 대소문자 무시 조회로 한 번 더 찾는다(여러 개면 입력과 정확히 같은 아이디만 인정).
 */
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

    /**
     * 가입. 같은 아이디(대소문자 무시)가 있으면 username-taken 400 이다. 확인과 저장 사이에 같은 아이디로 동시에 가입하면
     * 유일 제약 위반(DataIntegrityViolationException)이 나는데, 이것도 같은 응답으로 바꾼다.
     */
    @Transactional
    public PasswordAuthResult signUp(String username, String rawPassword, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        if (repository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw usernameTaken();
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
        UserCredential saved;
        try {
            // 커밋 때가 아니라 여기서 제약 위반이 나도록 바로 flush 한다.
            saved = repository.saveAndFlush(credential);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken();
        }
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
        String trimmedUsername = username == null ? "" : username.trim();
        String normalizedUsername = normalizeUsername(username);
        if (!attemptGuard.tryReserve(normalizedUsername, clientIp)) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            throw invalidCredentials();
        }

        Optional<UserCredential> found = findForLogin(normalizedUsername, trimmedUsername);
        String hash = found.map(UserCredential::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);
        if (found.isEmpty() || !matches) {
            throw invalidCredentials();
        }

        attemptGuard.recordSuccess(normalizedUsername, clientIp);
        UserCredential credential = found.get();
        return new PasswordAuthResult(credential.getUserIdentifier(), credential.getDisplayName());
    }

    /** 정규화한 아이디 → 입력 그대로 → 대소문자 무시(유일할 때만) 순서로 찾는다. */
    private Optional<UserCredential> findForLogin(String normalizedUsername, String trimmedUsername) {
        Optional<UserCredential> exact = repository.findByUsername(normalizedUsername);
        if (exact.isPresent()) {
            return exact;
        }
        if (!trimmedUsername.equals(normalizedUsername)) {
            Optional<UserCredential> asTyped = repository.findByUsername(trimmedUsername);
            if (asTyped.isPresent()) {
                return asTyped;
            }
        }
        List<UserCredential> legacy = repository.findByUsernameIgnoreCase(normalizedUsername);
        return legacy.size() == 1 ? Optional.of(legacy.get(0)) : Optional.empty();
    }

    static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static BadRequestException usernameTaken() {
        return new BadRequestException(Messages.get("auth.username-taken"));
    }

    private static BadRequestException invalidCredentials() {
        return new BadRequestException(Messages.get("auth.invalid-credentials"));
    }
}
