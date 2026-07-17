// 레벨 → 캐릭터 성장 단계 매핑. iOS(GrowthStage)와 반드시 동일한 구간을 유지해야 한다.

export type StageKey = 'seed' | 'sprout' | 'sapling' | 'young' | 'tree' | 'blossom'

export interface GrowthStage {
  key: StageKey
  name: string
  minLevel: number
}

export const GROWTH_STAGES: GrowthStage[] = [
  { key: 'seed', name: '씨앗', minLevel: 1 },
  { key: 'sprout', name: '새싹', minLevel: 3 },
  { key: 'sapling', name: '줄기', minLevel: 6 },
  { key: 'young', name: '어린나무', minLevel: 10 },
  { key: 'tree', name: '나무', minLevel: 15 },
  { key: 'blossom', name: '개화', minLevel: 20 },
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
