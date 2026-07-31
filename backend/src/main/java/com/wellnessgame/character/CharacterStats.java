package com.wellnessgame.character;

import jakarta.persistence.Embeddable;

@Embeddable
public class CharacterStats {
    private int str;
    private int vit;
    private int intStat;
    private int discipline;
    private int recovery;

    protected CharacterStats() {
    }

    public CharacterStats(int str, int vit, int intStat, int discipline, int recovery) {
        this.str = str;
        this.vit = vit;
        this.intStat = intStat;
        this.discipline = discipline;
        this.recovery = recovery;
    }

    public static CharacterStats initial() {
        return new CharacterStats(0, 0, 0, 0, 0);
    }

    public void addStrength(int amount) {
        str += amount;
    }

    public void addVitality(int amount) {
        vit += amount;
    }

    public void addIntelligence(int amount) {
        intStat += amount;
    }

    public void addDiscipline(int amount) {
        discipline += amount;
    }

    public void addRecovery(int amount) {
        recovery += amount;
    }

    public int getStr() {
        return str;
    }

    public int getVit() {
        return vit;
    }

    public int getIntStat() {
        return intStat;
    }

    public int getDiscipline() {
        return discipline;
    }

    public int getRecovery() {
        return recovery;
    }
}

