package com.wellnessgame.api;

import com.wellnessgame.character.UserCharacterRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/characters")
public class CharacterController {
    private final UserCharacterRepository characterRepository;

    public CharacterController(UserCharacterRepository characterRepository) {
        this.characterRepository = characterRepository;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<CharacterSummaryResponse> character(@PathVariable String userId) {
        return characterRepository.findByUserId(userId)
                .map(CharacterSummaryResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
