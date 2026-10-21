package com.wellnessgame.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 레벨업 경계: 다음 레벨 필요 XP = 100 + (레벨-1)*50. */
class UserCharacterLevelTest {

    @Test
    void newCharacterStartsAtLevelOneWithZeroStats() {
        UserCharacter character = new UserCharacter("u");

        assertThat(character.getLevel()).isEqualTo(1);
        assertThat(character.getCurrentXp()).isZero();
        assertThat(character.getTotalXp()).isZero();
        assertThat(character.nextLevelXp()).isEqualTo(100);
        assertThat(character.getStats().getStr() + character.getStats().getVit() + character.getStats().getIntStat()
                + character.getStats().getDiscipline() + character.getStats().getRecovery()).isZero();
    }

    @Test
    void zeroOrNegativeXpIsIgnored() {
        UserCharacter character = new UserCharacter("u");

        assertThat(character.addXp(0)).isFalse();
        assertThat(character.addXp(-50)).isFalse();
        assertThat(character.getTotalXp()).isZero();
        assertThat(character.getCurrentXp()).isZero();
    }

    @Test
    void levelUpHappensExactlyAtThreshold() {
        UserCharacter character = new UserCharacter("u");

        assertThat(character.addXp(99)).isFalse();
        assertThat(character.getLevel()).isEqualTo(1);
        assertThat(character.addXp(1)).isTrue();
        assertThat(character.getLevel()).isEqualTo(2);
        assertThat(character.getCurrentXp()).isZero();
        assertThat(character.nextLevelXp()).isEqualTo(150);
    }

    @ParameterizedTest(name = "총 {0} XP -> 레벨 {1}, 잔여 {2}")
    @CsvSource({
            "0,1,0",
            "100,2,0",
            "249,2,149",
            "250,3,0",     // 100 + 150
            "450,4,0",     // + 200
            "700,5,0",     // + 250
            "699,4,249"
    })
    void cumulativeThresholds(int totalXp, int level, int currentXp) {
        UserCharacter character = new UserCharacter("u");

        character.addXp(totalXp);

        assertThat(character.getLevel()).isEqualTo(level);
        assertThat(character.getCurrentXp()).isEqualTo(currentXp);
        assertThat(character.getTotalXp()).isEqualTo(totalXp);
    }

    @Test
    void splittingXpGivesSameResultAsGrantingAtOnce() {
        UserCharacter once = new UserCharacter("u");
        UserCharacter split = new UserCharacter("u");

        once.addXp(1_234);
        for (int i = 0; i < 1_234; i++) {
            split.addXp(1);
        }

        assertThat(split.getLevel()).isEqualTo(once.getLevel());
        assertThat(split.getCurrentXp()).isEqualTo(once.getCurrentXp());
        assertThat(split.getTotalXp()).isEqualTo(once.getTotalXp());
    }

    @Test
    void statsAccumulate() {
        CharacterStats stats = CharacterStats.initial();

        stats.addStrength(2);
        stats.addVitality(3);
        stats.addIntelligence(4);
        stats.addDiscipline(5);
        stats.addRecovery(6);
        stats.addStrength(1);

        assertThat(stats.getStr()).isEqualTo(3);
        assertThat(stats.getVit()).isEqualTo(3);
        assertThat(stats.getIntStat()).isEqualTo(4);
        assertThat(stats.getDiscipline()).isEqualTo(5);
        assertThat(stats.getRecovery()).isEqualTo(6);
    }
}
