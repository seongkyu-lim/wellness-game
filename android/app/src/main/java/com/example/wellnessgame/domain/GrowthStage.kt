package com.example.wellnessgame.domain

/**
 * 레벨 → 캐릭터 성장 단계. iOS `GrowthStage`, 웹 `growthStage.ts`와 같은 구간을 유지해야 한다.
 * 화면 이름은 `R.string.stage_*`(용어는 ios/GLOSSARY.md)로 만든다.
 */
enum class GrowthStage(val minLevel: Int) {
    SEED(1),
    SPROUT(3),
    SAPLING(6),
    YOUNG(10),
    TREE(15),
    BLOSSOM(20);

    companion object {
        fun forLevel(level: Int): GrowthStage = entries.lastOrNull { level >= it.minLevel } ?: SEED

        /** 다음 성장 단계. 마지막 단계면 null. */
        fun nextAfter(level: Int): GrowthStage? = entries.firstOrNull { it.minLevel > maxOf(level, 1) }
    }
}
