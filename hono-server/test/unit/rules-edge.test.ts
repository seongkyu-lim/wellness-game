import { describe, expect, it } from 'vitest';
import type { ActivityPayload } from '../../src/activity/types.js';
import { HealthActivitySyncValidator } from '../../src/activity/validator.js';
import {
  DAILY_WORKOUT_XP_CAP,
  DAILY_XP_CAP,
  calculateXp,
  resolvedSleepScore,
  sleepScore,
  sleepXp,
  stepsXp,
  workoutXp,
} from '../../src/activity/xp-calculator.js';
import { UserCharacter, characterResponse, saturatedAdd } from '../../src/character/character.js';
import { ApiError, resolve } from '../../src/lib/errors.js';
import { Instant } from '../../src/lib/instant.js';
import { T0 } from '../support/fixtures.js';

const at = (iso: string) => Instant.parse(iso)!;
const payload = (o: Partial<ActivityPayload>): ActivityPayload => ({
  type: 'WORKOUT',
  workoutType: null,
  durationMinutes: null,
  calories: null,
  distanceMeters: null,
  steps: null,
  sleepMinutes: null,
  sleepScore: null,
  startedAt: null,
  endedAt: null,
  ...o,
});

describe('XP 경계값', () => {
  it.each([
    [null, 0],
    [-500, 0],
    [999, 0],
    [1000, 5],
    [7999, 35],
    [8000, 70], // 40 + 보너스 30
    [10_000, 80],
    [13_999, 95], // 13*5 + 30
    [14_000, 100],
    [100_000, 100], // 상한
  ])('걸음 %s → %d XP', (steps, xp) => {
    expect(stepsXp(steps)).toBe(xp);
  });

  it.each([
    // [분, 칼로리, 종목, XP]
    [null, 0, 'RUNNING', 0],
    [4, 1000, 'SWIMMING', 0], // 5분 미만은 종목 보너스도 없음
    [5, 0, 'OTHER', 15],
    [5, 0, null, 15],
    [5, 9.99, 'SWIMMING', 35], // 15 + floor(0.999)=0 + 20
    [5, 10, 'RUNNING', 31], // 15 + 1 + 15
    [30, 300, 'WALKING', 125], // 90 + 30 + 5
    [30, 300, 'STRENGTH_TRAINING', 135],
    [60, 600, 'CYCLING', 200], // 180 + 60 + 15 = 255 → 상한 200
    [720, 0, 'OTHER', 200],
  ] as const)('운동 %s분 %s kcal %s → %d XP', (minutes, calories, workoutType, xp) => {
    expect(workoutXp(payload({ durationMinutes: minutes, calories, workoutType }))).toBe(xp);
  });

  it.each([
    // [분, 기본 점수 계산 시 XP]
    [0, 10],
    [299, 10],
    [300, 25],
    [359, 25],
    [360, 40],
    [419, 40],
    [420, 80], // 60 + 품질 보너스 20
    [540, 80],
    [541, 40], // 540 초과는 기본 XP 40, 점수 70 이라 품질 보너스도 없음
  ])('수면 %d분(점수 미제공) → %d XP', (minutes, xp) => {
    expect(sleepXp(minutes, null)).toBe(xp);
  });

  it('수면 점수: 제공값은 0~100 으로 자르고, 미제공이면 시간으로 계산', () => {
    expect(resolvedSleepScore(480, 150)).toBe(100);
    expect(resolvedSleepScore(480, -20)).toBe(0);
    expect(resolvedSleepScore(480, null)).toBe(90);
    expect([299, 300, 359, 360, 419, 420, 540, 541].map(sleepScore)).toEqual([30, 50, 50, 70, 70, 90, 90, 70]);
  });

  it('수면 품질 보너스는 점수 80 이상일 때만(시간은 충분해도 점수가 낮으면 없음)', () => {
    expect(sleepXp(480, 79)).toBe(60);
    expect(sleepXp(480, 80)).toBe(80);
    expect(sleepXp(200, 100)).toBe(30); // 짧아도 점수가 높으면 보너스
  });

  it('calculateXp 는 유형별 계산기로 위임하고, 일일 상한 상수는 걸음 100 + 수면 80 + 운동 400 이내', () => {
    expect(calculateXp(payload({ type: 'STEPS', steps: 8000 }))).toBe(70);
    expect(calculateXp(payload({ type: 'SLEEP', sleepMinutes: 480 }))).toBe(80);
    expect(calculateXp(payload({ type: 'WORKOUT', durationMinutes: 10, workoutType: 'RUNNING' }))).toBe(45);
    expect(DAILY_WORKOUT_XP_CAP).toBeLessThan(DAILY_XP_CAP);
    expect(100 + 80 + DAILY_WORKOUT_XP_CAP).toBeLessThanOrEqual(DAILY_XP_CAP);
  });
});

describe('UserCharacter', () => {
  const fresh = () => UserCharacter.create('u1');

  it('레벨업 경계: 필요 XP 와 같으면 레벨업하고 남은 XP 는 0, 1 모자라면 그대로', () => {
    const a = fresh();
    expect(a.addXp(99)).toBe(false);
    expect([a.level, a.currentXp]).toEqual([1, 99]);
    expect(a.addXp(1)).toBe(true);
    expect([a.level, a.currentXp, a.totalXp, a.nextLevelXp()]).toEqual([2, 0, 100, 150]);
  });

  it('0 이하 XP 는 변화 없음', () => {
    const c = fresh();
    expect(c.addXp(0)).toBe(false);
    expect(c.addXp(-10)).toBe(false);
    expect(c.totalXp).toBe(0);
  });

  it('int 상한에서 포화하고 무한 반복 없이 끝난다', () => {
    const c = fresh();
    c.totalXp = 2_147_483_600;
    expect(c.addXp(1000)).toBe(true);
    expect(c.totalXp).toBe(2_147_483_647);
    expect(c.currentXp).toBeLessThan(c.nextLevelXp());
    expect(saturatedAdd(2_147_483_647, 1)).toBe(2_147_483_647);
    expect(saturatedAdd(1, 2)).toBe(3);
  });

  it('응답 변환은 스탯 다섯 개와 다음 레벨 XP 를 담는다', () => {
    expect(characterResponse(fresh())).toEqual({
      level: 1,
      currentXp: 0,
      totalXp: 0,
      nextLevelXp: 100,
      stats: { str: 0, vit: 0, intStat: 0, discipline: 0, recovery: 0 },
    });
  });
});

describe('동기화 검증 경계(서울 2026-10-20 12:00)', () => {
  const validator = new HealthActivitySyncValidator('Asia/Seoul', () => T0);
  const date = '2026-10-20';
  const why = (p: ActivityPayload, d = date) => {
    const r = validator.rejection(d, p);
    return r ? resolve('ko', r) : null;
  };
  const structural = (activities: ActivityPayload[] | null, d: string | null = date) => {
    try {
      validator.validate(d, activities);
    } catch (error) {
      if (error instanceof ApiError) return resolve('ko', error.localized);
      throw error;
    }
    return null;
  };
  const workout = (start: string, end: string, o: Partial<ActivityPayload> = {}) =>
    payload({ startedAt: at(start), endedAt: at(end), ...o });

  it('요청 구조: 활동 50개까지, 51개는 거부, 날짜는 오늘 ±1일(포함)', () => {
    const steps = payload({ type: 'STEPS', steps: 1 });
    expect(structural(Array(50).fill(steps))).toBeNull();
    expect(structural(Array(51).fill(steps))).toBe('한 번에 최대 50개의 활동까지 동기화할 수 있습니다.');
    expect(structural(null)).toBe('동기화할 활동이 없습니다.');
    expect(structural([steps], null)).toBe('동기화 날짜가 필요합니다.');
    expect(structural([steps], '2026-10-19')).toBeNull();
    expect(structural([steps], '2026-10-21')).toBeNull();
    expect(structural([steps], '2026-10-18')).toMatch(/±1일/);
    expect(structural([steps], '2026-10-22')).toMatch(/±1일/);
  });

  it('활동별 필수 필드: STEPS.steps, SLEEP 3개, 종료가 시작보다 앞서면 거부(같으면 구조는 통과)', () => {
    expect(structural([payload({ type: 'STEPS' })])).toMatch(/steps/);
    expect(structural([payload({ type: 'SLEEP', sleepMinutes: 1 })])).toMatch(/sleep/i);
    expect(structural([workout('2026-10-20T01:00:00Z', '2026-10-20T01:00:00Z')])).toBeNull();
    expect(why(workout('2026-10-20T01:00:00Z', '2026-10-20T01:00:00Z'))).toBe('운동 종료 시간은 시작 시간보다 늦어야 합니다.');
  });

  it('미래 시각 허용 오차: 정확히 5분은 통과, 1초 넘으면 거부', () => {
    expect(why(workout('2026-10-20T02:50:00Z', '2026-10-20T03:05:00Z', { durationMinutes: 15 }))).toBeNull();
    expect(why(workout('2026-10-20T02:50:00Z', '2026-10-20T03:05:01Z', { durationMinutes: 15 }))).toBe('활동 시간은 현재 시각 이후일 수 없습니다.');
  });

  it('운동 길이 상한: 12시간 정확히는 통과, 12시간 1분은 거부', () => {
    expect(why(workout('2026-10-19T15:00:00Z', '2026-10-20T03:00:00Z'))).toBeNull();
    expect(why(workout('2026-10-19T14:59:00Z', '2026-10-20T03:00:00Z'))).toMatch(/12시간/);
  });

  it('칼로리: 분당 20kcal 정확히는 통과, 0.01 넘으면 거부. 절대 상한 10000, 거리 상한 500000', () => {
    const base = ['2026-10-20T01:00:00Z', '2026-10-20T01:10:00Z'] as const;
    expect(why(workout(...base, { calories: 200 }))).toBeNull();
    expect(why(workout(...base, { calories: 200.01 }))).toMatch(/분당 최대 20/);
    expect(why(workout('2026-10-19T16:00:00Z', '2026-10-20T03:00:00Z', { calories: 10_001 }))).toMatch(/10000/);
    expect(why(workout('2026-10-19T16:00:00Z', '2026-10-20T03:00:00Z', { calories: -1 }))).toMatch(/10000/);
    expect(why(workout(...base, { calories: 0, distanceMeters: 500_000 }))).toBeNull();
    expect(why(workout(...base, { distanceMeters: 500_000.01 }))).toMatch(/500000/);
  });

  it('칼로리 분당 한도는 초 단위 구간 길이로 본다(10분 30초 → 210kcal 까지)', () => {
    const w = (calories: number) => workout('2026-10-20T01:00:00Z', '2026-10-20T01:10:30Z', { calories, durationMinutes: 11 });
    expect(why(w(210))).toBeNull();
    expect(why(w(210.01))).toMatch(/분당/);
  });

  it('수면: 점수 0~100 만, 960분(16시간) 초과 거부, 깬 날짜 ±1일 경계', () => {
    const sleep = (o: Partial<ActivityPayload>) =>
      payload({ type: 'SLEEP', startedAt: at('2026-10-19T14:00:00Z'), endedAt: at('2026-10-19T22:00:00Z'), sleepMinutes: 480, ...o });
    expect(why(sleep({ sleepScore: 0 }))).toBeNull();
    expect(why(sleep({ sleepScore: 100 }))).toBeNull();
    expect(why(sleep({ sleepScore: 101 }))).toMatch(/0.*100/);
    expect(why(sleep({ sleepScore: -1 }))).toMatch(/0.*100/);
    expect(why(sleep({ sleepMinutes: 961, startedAt: at('2026-10-19T05:00:00Z') }))).toMatch(/960/);
    // 깬 날짜(서울)가 date+1 인 수면: 허용, date+2 는 창 위반
    expect(why(sleep({ startedAt: at('2026-10-20T14:00:00Z'), endedAt: at('2026-10-20T22:00:00Z') }), '2026-10-20')).toMatch(/현재 시각|날짜/);
  });

  it('걸음: 0·100000 은 통과, 내일 날짜는 거부(어제는 허용)', () => {
    expect(why(payload({ type: 'STEPS', steps: 0 }))).toBeNull();
    expect(why(payload({ type: 'STEPS', steps: 100_000 }))).toBeNull();
    expect(why(payload({ type: 'STEPS', steps: 5 }), '2026-10-19')).toBeNull();
    expect(why(payload({ type: 'STEPS', steps: 5 }), '2026-10-21')).toMatch(/오늘 이후/);
  });

  it('영어 문구', () => {
    const r = validator.rejection(date, workout('2026-10-20T01:00:00Z', '2026-10-20T01:00:00Z'))!;
    expect(resolve('en', r)).toBe('A workout must end after it starts.');
  });
});
