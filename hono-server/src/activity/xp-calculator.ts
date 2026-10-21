import type { ActivityPayload, WorkoutType } from './types.js';

/** XP 규칙(Spring ActivityXpCalculator). 상수 이름·값을 그대로 둔다. */
export const STEPS_GOAL = 8_000;
export const WORKOUT_GOAL_MINUTES = 30;
export const SLEEP_GOAL_MINUTES = 420;

/** 사용자·날짜별 지급 XP 합계 상한(걸음 100 + 수면 80 + 최대치 운동 2회 400 = 580 을 반올림) */
export const DAILY_XP_CAP = 600;
/** 사용자·날짜별 WORKOUT 지급 XP 합계 상한(최대치 운동 2회분) */
export const DAILY_WORKOUT_XP_CAP = 400;

const STEPS_XP_CAP = 100;
const WORKOUT_XP_CAP = 200;
export const SLEEP_QUALITY_BONUS_SCORE = 80;

/**
 * XP·스탯을 주는 최소 운동 시간(분, #107). 시작~종료 구간이나 활동 시간(durationMinutes)이 이보다 짧으면 0 XP 다.
 * 종목 보너스가 시간과 무관한 고정값이라 짧은 운동을 여러 번 보내 보너스만 챙기는 것을 막는다.
 */
export const MIN_WORKOUT_MINUTES = 5;

export function calculateXp(activity: ActivityPayload): number {
  switch (activity.type) {
    case 'STEPS':
      return stepsXp(activity.steps);
    case 'WORKOUT':
      return workoutXp(activity);
    case 'SLEEP':
      return sleepXp(activity.sleepMinutes, activity.sleepScore);
  }
}

export function stepsXp(steps: number | null): number {
  const value = Math.max(steps ?? 0, 0);
  let xp = Math.trunc(value / 1000) * 5;
  if (value >= STEPS_GOAL) xp += 30;
  return Math.min(xp, STEPS_XP_CAP);
}

export function workoutXp(activity: ActivityPayload): number {
  if ((activity.durationMinutes ?? 0) < MIN_WORKOUT_MINUTES) return 0; // 최소 운동 시간 미만은 종목 보너스도 없음
  const xp = (activity.durationMinutes ?? 0) * 3.0 + Math.floor((activity.calories ?? 0) / 10.0) + workoutTypeBonus(activity.workoutType);
  return clamp(javaRound(xp), 0, WORKOUT_XP_CAP);
}

function workoutTypeBonus(type: WorkoutType | null): number {
  switch (type ?? 'OTHER') {
    case 'SWIMMING':
      return 20;
    case 'RUNNING':
    case 'CYCLING':
    case 'STRENGTH_TRAINING':
      return 15;
    case 'WALKING':
      return 5;
    default:
      return 0;
  }
}

export function sleepXp(sleepMinutes: number | null, providedScore: number | null): number {
  const minutes = sleepMinutes ?? 0;
  let xp = sleepBaseXp(minutes);
  if (resolvedSleepScore(minutes, providedScore) >= SLEEP_QUALITY_BONUS_SCORE) xp += 20;
  return xp;
}

function sleepBaseXp(minutes: number): number {
  if (minutes >= 420 && minutes <= 540) return 60;
  if (minutes > 540) return 40;
  if (minutes >= 360) return 40;
  if (minutes >= 300) return 25;
  return 10;
}

export function resolvedSleepScore(minutes: number, providedScore: number | null): number {
  return providedScore === null ? sleepScore(minutes) : clamp(providedScore, 0, 100);
}

export function sleepScore(minutes: number): number {
  if (minutes >= 420 && minutes <= 540) return 90;
  if (minutes >= 360) return 70;
  if (minutes >= 300) return 50;
  return 30;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Math.round(double) 와 같다(0.5 는 올림) */
function javaRound(value: number): number {
  return Math.floor(value + 0.5);
}
