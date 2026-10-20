package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordAuthServiceTest {
    private Map<String, UserCredential> store;
    private PasswordAuthService service;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        UserCredentialRepository repository = InMemoryCredentialRepository.create(store);

        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        service = new PasswordAuthService(repository, passwordEncoder);
    }

    @Test
    void signUpIssuesStableIdentifierAndDefaultsDisplayNameToUsername() {
        PasswordAuthResult result = service.signUp("wellness_user", "password123", null);

        assertThat(result.userIdentifier()).isNotBlank();
        assertThat(result.displayName()).isEqualTo("wellness_user");
        // 저장된 해시는 원문과 달라야 한다.
        assertThat(store.get("wellness_user").getPasswordHash()).isNotEqualTo("password123");
    }

    @Test
    void signUpUsesProvidedDisplayName() {
        PasswordAuthResult result = service.signUp("runner01", "password123", "달리는사람");
        assertThat(result.displayName()).isEqualTo("달리는사람");
    }

    @Test
    void signUpRejectsDuplicateUsername() {
        service.signUp("dup_user", "password123", null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.signUp("dup_user", "another-pass", null))
                .withMessageContaining("이미 사용 중인 아이디");
    }

    @Test
    void signInReturnsSameIdentifierAsSignUp() {
        PasswordAuthResult signedUp = service.signUp("stable_user", "password123", null);
        PasswordAuthResult signedIn = service.signIn("stable_user", "password123");
        assertThat(signedIn.userIdentifier()).isEqualTo(signedUp.userIdentifier());
    }

    @Test
    void signInRejectsWrongPassword() {
        service.signUp("secure_user", "password123", null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.signIn("secure_user", "wrong-password"))
                .withMessageContaining("올바르지 않");
    }

    @Test
    void signInRejectsUnknownUsername() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.signIn("ghost_" + UUID.randomUUID(), "password123"))
                .withMessageContaining("올바르지 않");
    }

    @Test
    void concurrentDuplicateSignupConstraintViolationMapsToUsernameTaken() {
        UserCredentialRepository racing = mock(UserCredentialRepository.class);
        // 확인 시점에는 없었지만 저장 직전에 다른 요청이 같은 아이디로 가입했다.
        when(racing.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(racing.saveAndFlush(any(UserCredential.class)))
                .thenThrow(new DataIntegrityViolationException("uk_credential_username"));
        PasswordAuthService racingService = new PasswordAuthService(racing, new BCryptPasswordEncoder(4));

        assertThatThrownBy(() -> racingService.signUp("race_user", "password123", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("이미 사용 중인 아이디");
    }

    @Test
    void signUpNormalizesUsernameToLowercase() {
        service.signUp("  Upper_Case  ", "password123", null);
        assertThat(store).containsKey("upper_case");
        assertThat(service.signIn("UPPER_CASE", "password123").displayName()).isEqualTo("upper_case");
    }

    @Test
    void ambiguousLegacyCaseVariantsRequireExactUsername() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        store.put("Twin_A", new UserCredential("Twin_A", encoder.encode("password123"), "id-1", "one"));
        store.put("TWIN_a", new UserCredential("TWIN_a", encoder.encode("password123"), "id-2", "two"));

        assertThat(service.signIn("Twin_A", "password123").userIdentifier()).isEqualTo("id-1");
        assertThat(service.signIn("TWIN_a", "password123").userIdentifier()).isEqualTo("id-2");
        assertThatIllegalArgumentException().isThrownBy(() -> service.signIn("twin_A", "password123"));
    }

    /** #106 정규화 이전의 'Alice' 와 새 'alice' 가 함께 있어도 각자 자기 아이디로 로그인된다(입력 그대로를 먼저 찾음). */
    @Test
    void legacyMixedCaseAccountCoexistsWithLowercaseAccount() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        store.put("Alice_01", new UserCredential("Alice_01", encoder.encode("legacy-pass"), "legacy-alice", "Legacy"));
        store.put("alice_01", new UserCredential("alice_01", encoder.encode("new-pass1"), "new-alice", "New"));

        assertThat(service.signIn("Alice_01", "legacy-pass").userIdentifier()).isEqualTo("legacy-alice");
        assertThat(service.signIn("alice_01", "new-pass1").userIdentifier()).isEqualTo("new-alice");
        // 다른 철자는 소문자 계정으로 간다.
        assertThat(service.signIn("ALICE_01", "new-pass1").userIdentifier()).isEqualTo("new-alice");
    }
}
