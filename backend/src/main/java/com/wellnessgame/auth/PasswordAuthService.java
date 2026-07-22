package com.wellnessgame.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PasswordAuthService {
    private final UserCredentialRepository repository;
    private final PasswordEncoder passwordEncoder;

    public PasswordAuthService(UserCredentialRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public PasswordAuthResult signUp(String username, String rawPassword, String displayName) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (repository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다.");
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

    @Transactional(readOnly = true)
    public PasswordAuthResult signIn(String username, String rawPassword) {
        String normalizedUsername = username == null ? "" : username.trim();
        UserCredential credential = repository.findByUsername(normalizedUsername)
                .filter(found -> passwordEncoder.matches(rawPassword, found.getPasswordHash()))
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다."));
        return new PasswordAuthResult(credential.getUserIdentifier(), credential.getDisplayName());
    }
}
