package com.wellnessgame.api;

import com.wellnessgame.auth.AuthenticatedUser;
import com.wellnessgame.auth.UserAccess;
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

    /** 토큰 주체의 캐릭터. 응답 형식은 {@link #character} 와 같다. */
    @GetMapping("/me")
    public ResponseEntity<CharacterSummaryResponse> me(@AuthenticatedUser String authenticatedUserId) {
        return find(authenticatedUserId);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<CharacterSummaryResponse> character(
            @AuthenticatedUser String authenticatedUserId,
            @PathVariable String userId
    ) {
        return find(UserAccess.resolve(authenticatedUserId, userId));
    }

    private ResponseEntity<CharacterSummaryResponse> find(String userId) {
        return characterRepository.findByUserId(userId)
                .map(CharacterSummaryResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
