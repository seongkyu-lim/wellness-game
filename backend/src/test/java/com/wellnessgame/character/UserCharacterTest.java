package com.wellnessgame.character;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserCharacterTest {
    @Test
    void carriesXpAcrossMultipleLevelUps() {
        UserCharacter character = new UserCharacter("test-user");

        boolean levelUp = character.addXp(650);

        assertThat(levelUp).isTrue();
        assertThat(character.getLevel()).isEqualTo(4);        // 100 + 150 + 200 소진
        assertThat(character.getCurrentXp()).isEqualTo(200);  // 잔여 이월
        assertThat(character.getTotalXp()).isEqualTo(650);
        assertThat(character.nextLevelXp()).isEqualTo(250);   // 100 + (4-1)*50
    }
}

