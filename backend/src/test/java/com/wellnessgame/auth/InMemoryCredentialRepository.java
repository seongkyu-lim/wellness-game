package com.wellnessgame.auth;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 아이디 → 계정 맵으로 동작하는 {@link UserCredentialRepository} 목. 서비스 단위 테스트용. */
final class InMemoryCredentialRepository {
    private InMemoryCredentialRepository() {
    }

    static UserCredentialRepository create(Map<String, UserCredential> store) {
        UserCredentialRepository repository = mock(UserCredentialRepository.class);
        when(repository.existsByUsername(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(repository.existsByUsernameIgnoreCase(anyString())).thenAnswer(inv -> store.keySet().stream()
                .anyMatch(username -> username.equalsIgnoreCase(inv.getArgument(0))));
        when(repository.findByUsername(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(repository.findByUsernameIgnoreCase(anyString())).thenAnswer(inv -> store.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(inv.getArgument(0)))
                .map(Map.Entry::getValue)
                .toList());
        when(repository.saveAndFlush(any(UserCredential.class))).thenAnswer(inv -> {
            UserCredential credential = inv.getArgument(0);
            store.put(credential.getUsername(), credential);
            return credential;
        });
        return repository;
    }
}
