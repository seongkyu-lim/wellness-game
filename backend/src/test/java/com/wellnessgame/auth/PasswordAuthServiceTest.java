package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
        UserCredentialRepository repository = mock(UserCredentialRepository.class);
        when(repository.existsByUsername(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(repository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repository.save(any(UserCredential.class))).thenAnswer(inv -> {
            UserCredential credential = inv.getArgument(0);
            store.put(credential.getUsername(), credential);
            return credential;
        });

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
}
