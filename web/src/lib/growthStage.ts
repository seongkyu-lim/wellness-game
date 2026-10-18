// 레벨 → 캐릭터 성장 단계 매핑. iOS(GrowthStage)와 반드시 동일한 구간을 유지해야 한다.
// 표시 이름은 i18n 사전의 `stage.<key>`에서 가져온다.

export type StageKey = 'seed' | 'sprout' | 'sapling' | 'young' | 'tree' | 'blossom'

export interface GrowthStage {
  key: StageKey
  minLevel: number
}

export const GROWTH_STAGES: GrowthStage[] = [
  { key: 'seed', minLevel: 1 },
  { key: 'sprout', minLevel: 3 },
  { key: 'sapling', minLevel: 6 },
  { key: 'young', minLevel: 10 },
  { key: 'tree', minLevel: 15 },
  { key: 'blossom', minLevel: 20 },
]

export function growthStage(level: number): GrowthStage {
  let current = GROWTH_STAGES[0]
  for (const stage of GROWTH_STAGES) {
    if (level >= stage.minLevel) {
      current = stage
    }
  }
  return current
}

/** 다음 성장 단계. 마지막 단계면 null. */
export function nextStage(level: number): GrowthStage | null {
  return GROWTH_STAGES.find((stage) => stage.minLevel > Math.max(level, 1)) ?? null
}
