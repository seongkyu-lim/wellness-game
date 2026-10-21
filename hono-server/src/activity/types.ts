import type { LocalDate } from '../lib/dates.js';
import type { Instant } from '../lib/instant.js';

/** health_activity_log.type 값(check 제약 ck_health_activity_type 과 같음) */
export const ACTIVITY_TYPES = ['STEPS', 'WORKOUT', 'SLEEP'] as const;
export type ActivityType = (typeof ACTIVITY_TYPES)[number];

export const WORKOUT_TYPES = ['SWIMMING', 'RUNNING', 'WALKING', 'CYCLING', 'STRENGTH_TRAINING', 'OTHER'] as const;
export type WorkoutType = (typeof WORKOUT_TYPES)[number];

/** POST /api/health-activities/sync 의 activities[] 항목(HealthActivitySyncRequest.ActivityPayload) */
export interface ActivityPayload {
  type: ActivityType;
  workoutType: WorkoutType | null;
  durationMinutes: number | null;
  calories: number | null;
  distanceMeters: number | null;
  steps: number | null;
  sleepMinutes: number | null;
  sleepScore: number | null;
  startedAt: Instant | null;
  endedAt: Instant | null;
}

export interface SyncRequest {
  /** 토큰 주체로 확정된 userId */
  userId: string;
  date: LocalDate;
  activities: ActivityPayload[];
}
