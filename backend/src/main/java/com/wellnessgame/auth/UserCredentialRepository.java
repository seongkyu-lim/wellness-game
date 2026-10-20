package com.wellnessgame.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
    Optional<UserCredential> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 소문자 정규화(#106) 이전에 대소문자를 섞어 가입한 계정까지 찾는다. */
    boolean existsByUsernameIgnoreCase(String username);

    List<UserCredential> findByUsernameIgnoreCase(String username);
}
