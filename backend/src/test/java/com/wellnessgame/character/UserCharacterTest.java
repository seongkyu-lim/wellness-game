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

    @Test
    void saturatesXpInsteadOfOverflowing() {
        UserCharacter character = new UserCharacter("test-user");

        character.addXp(Integer.MAX_VALUE);
        int levelAfterFirst = character.getLevel();
        character.addXp(Integer.MAX_VALUE);

        assertThat(character.getTotalXp()).isEqualTo(Integer.MAX_VALUE);
        assertThat(character.getCurrentXp()).isBetween(0, character.nextLevelXp() - 1);
        assertThat(character.getLevel()).isGreaterThan(levelAfterFirst);
        assertThat(character.nextLevelXp()).isPositive();
    }
}
