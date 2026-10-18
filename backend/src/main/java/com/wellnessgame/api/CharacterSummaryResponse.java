package com.wellnessgame.api;

import com.wellnessgame.api.HealthActivitySyncResponse.CharacterResponse;
import com.wellnessgame.character.UserCharacter;

public record CharacterSummaryResponse(
        String userId,
        CharacterResponse character
) {
    public static CharacterSummaryResponse from(UserCharacter character) {
        return new CharacterSummaryResponse(character.getUserId(), CharacterResponse.from(character));
    }
}
