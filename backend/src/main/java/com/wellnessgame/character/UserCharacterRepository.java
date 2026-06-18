package com.wellnessgame.character;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserCharacterRepository extends JpaRepository<UserCharacter, UUID> {
    Optional<UserCharacter> findByUserId(String userId);
}

