package com.wellnessgame.character;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserCharacterTest {
    @Test
    void carriesXpAcrossMultipleLevelUps() {
        UserCharacter character = new UserCharacter("test-user");

        boolean levelUp = character.addXp(650);

        assertThat(levelUp).isTrue();
        assertThat(character.getLevel()).isEqualTo(4);
        assertThat(character.getCurrentXp()).isEqualTo(50);
        assertThat(character.getTotalXp()).isEqualTo(650);
        assertThat(character.nextLevelXp()).isEqualTo(400);
    }
}

