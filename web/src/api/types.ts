// 백엔드 DTO(com.wellnessgame.api)와 필드명이 1:1로 일치해야 한다.

export type ActivityType = 'STEPS' | 'WORKOUT' | 'SLEEP'

export interface CharacterStats {
  str: number
  vit: number
  intStat: number
  discipline: number
  recovery: number
}

export interface Character {
  level: number
  currentXp: number
  totalXp: number
  nextLevelXp: number
  stats: CharacterStats
}

export interface CharacterSummary {
  userId: string
  character: Character
}

export interface ActivityEntry {
  type: ActivityType
  source: string
  durationMinutes: number | null
  calories: number | null
  distanceMeters: number | null
  steps: number | null
  sleepMinutes: number | null
  sleepScore: number | null
  gainedXp: number
  startedAt: string | null
  endedAt: string | null
}

export interface DailyActivitiesResponse {
  userId: string
  date: string
  activities: ActivityEntry[]
}
