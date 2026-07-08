// 백엔드 DTO(com.wellnessgame.api)와 필드명이 1:1로 일치해야 한다.

export type ActivityType = 'STEPS' | 'WORKOUT' | 'SLEEP'

export type WorkoutType =
  | 'SWIMMING'
  | 'RUNNING'
  | 'WALKING'
  | 'CYCLING'
  | 'STRENGTH_TRAINING'
  | 'OTHER'

export interface ActivityPayload {
  type: ActivityType
  workoutType?: WorkoutType
  durationMinutes?: number
  calories?: number
  distanceMeters?: number
  steps?: number
  sleepMinutes?: number
  sleepScore?: number
  startedAt?: string
  endedAt?: string
}

export interface SyncRequest {
  userId: string
  date: string
  activities: ActivityPayload[]
}

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

export interface ActivityResult {
  type: ActivityType
  source: string
  gainedXp: number
  message: string
  duplicate: boolean
}

export interface SyncResponse {
  userId: string
  date: string
  gainedXp: number
  levelUp: boolean
  character: Character
  activityResults: ActivityResult[]
}

export interface CharacterSummary {
  userId: string
  character: Character
}
