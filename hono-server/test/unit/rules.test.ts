import { describe, expect, it } from 'vitest';
import type { ActivityPayload } from '../../src/activity/types.js';
import { HealthActivitySyncValidator, spanMinutes } from '../../src/activity/validator.js';
import { sleepScore, sleepXp, stepsXp, workoutXp } from '../../src/activity/xp-calculator.js';
import { UserCharacter } from '../../src/character/character.js';
import { ApiError, resolve } from '../../src/lib/errors.js';
import { Instant } from '../../src/lib/instant.js';
import { T0 } from '../support/fixtures.js';

const at = (iso: string) => Instant.parse(iso)!;

function payload(overrides: Partial<ActivityPayload>): ActivityPayload {
  return {
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
    ...overrides,
  };
}

describe('XP 규칙(ActivityXpCalculator)', () => {
  it('걸음: 1,000보당 5, 8,000보 보너스 30, 상한 100', () => {
    expect(stepsXp(999)).toBe(0);
    expect(stepsXp(7999)).toBe(35);
    expect(stepsXp(8000)).toBe(70);
    expect(stepsXp(100_000)).toBe(100);
    expect(stepsXp(null)).toBe(0);
  });

  it('운동: 분×3 + floor(kcal/10) + 종목 보너스, 상한 200, 5분 미만 0(#107)', () => {
    expect(workoutXp(payload({ durationMinutes: 30, calories: 205, workoutType: 'SWIMMING' }))).toBe(90 + 20 + 20);
    expect(workoutXp(payload({ durationMinutes: 30, workoutType: 'WALKING' }))).toBe(95);
    expect(workoutXp(payload({ durationMinutes: 100, calories: 1000 }))).toBe(200);
    expect(workoutXp(payload({ durationMinutes: 4, workoutType: 'SWIMMING' }))).toBe(0);
    expect(workoutXp(payload({ durationMinutes: 5, workoutType: 'OTHER' }))).toBe(15);
  });

  it('수면: 시간 구간별 기본 + 점수 80 이상 보너스 20', () => {
    expect(sleepXp(480, null)).toBe(80);
    expect(sleepXp(600, null)).toBe(40);
    expect(sleepXp(370, null)).toBe(40);
    expect(sleepXp(300, 85)).toBe(45);
    expect(sleepXp(100, null)).toBe(10);
    expect(sleepScore(420)).toBe(90);
  });

  it('레벨: 다음 레벨 필요 XP = 100 + (level-1)×50, 여러 단계 레벨업', () => {
    const c = UserCharacter.create('u');
    expect(c.addXp(0)).toBe(false);
    expect(c.addXp(99)).toBe(false);
    expect(c.addXp(1)).toBe(true);
    expect([c.level, c.currentXp, c.nextLevelXp()]).toEqual([2, 0, 150]);
    expect(c.addXp(150 + 200 + 10)).toBe(true);
    expect([c.level, c.currentXp, c.totalXp]).toEqual([4, 10, 460]);
  });
});

describe('동기화 검증(HealthActivitySyncValidator)', () => {
  const validator = new HealthActivitySyncValidator('Asia/Seoul', () => T0); // 서울 2026-10-20 12:00
  const date = '2026-10-20';
  const reason = (p: ActivityPayload, d = date) => {
    const r = validator.rejection(d, p);
    return r ? resolve('ko', r) : null;
  };
  const structural = (fn: () => void) => {
    try {
      fn();
    } catch (error) {
      return error instanceof ApiError ? resolve('ko', error.localized) : String(error);
    }
    return null;
  };

  it('구조 오류는 400(요청 전체)', () => {
    expect(structural(() => validator.validate(date, []))).toBe('동기화할 활동이 없습니다.');
    expect(structural(() => validator.validate('2026-10-22', [payload({ type: 'STEPS', steps: 1 })]))).toBe(
      '동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.',
    );
    expect(structural(() => validator.validate(date, [payload({ type: 'WORKOUT' })]))).toBe(
      'WORKOUT 활동에는 startedAt과 endedAt이 필요합니다.',
    );
    expect(
      structural(() =>
        validator.validate(date, [payload({ startedAt: at('2026-10-20T01:00:00Z'), endedAt: at('2026-10-20T00:00:00Z') })]),
      ),
    ).toBe('활동 종료 시간은 시작 시간 이후여야 합니다.');
  });

  it('운동 시간은 반올림한 구간(분)을 넘을 수 없다(허용 오차 없음, #107)', () => {
    const start = at('2026-10-20T01:00:00Z');
    expect(spanMinutes(start, at('2026-10-20T01:29:30Z'))).toBe(30);
    expect(spanMinutes(start, at('2026-10-20T01:29:29Z'))).toBe(29);
    expect(reason(payload({ startedAt: start, endedAt: at('2026-10-20T01:29:30Z'), durationMinutes: 30 }))).toBeNull();
    expect(reason(payload({ startedAt: start, endedAt: at('2026-10-20T01:29:29Z'), durationMinutes: 30 }))).toBe(
      '운동 시간(durationMinutes)이 시작~종료 구간 길이보다 깁니다.',
    );
  });

  it('칼로리 분당 상한·미래 시각·날짜 창', () => {
    const start = at('2026-10-20T01:00:00Z');
    expect(reason(payload({ startedAt: start, endedAt: at('2026-10-20T01:10:00Z'), calories: 201 }))).toBe(
      '칼로리가 운동 시간 대비 너무 큽니다(분당 최대 20kcal).',
    );
    expect(reason(payload({ startedAt: start, endedAt: at('2026-10-20T03:06:00Z') }))).toBe('활동 시간은 현재 시각 이후일 수 없습니다.');
    expect(reason(payload({ startedAt: at('2026-10-18T14:59:59Z'), endedAt: at('2026-10-18T15:30:00Z') }))).toBe(
      '활동 시간이 동기화 날짜 범위를 벗어났습니다.',
    );
  });

  it('걸음: 오늘 이후 날짜 거부(#107), 범위', () => {
    expect(reason(payload({ type: 'STEPS', steps: 100 }), '2026-10-21')).toBe('오늘 이후 날짜의 걸음 수는 반영할 수 없습니다.');
    expect(reason(payload({ type: 'STEPS', steps: 100_001 }))).toBe('걸음 수는 0 이상 100000 이하여야 합니다.');
    expect(reason(payload({ type: 'STEPS', steps: 5000 }))).toBeNull();
  });

  it('수면: 깬 날짜(서울) ±1일 안의 date 만(#107), 분 값은 구간 이하', () => {
    const sleep = (start: string, end: string, minutes: number) =>
      payload({ type: 'SLEEP', startedAt: at(start), endedAt: at(end), sleepMinutes: minutes });
    // 서울 10/20 07:00 기상
    expect(reason(sleep('2026-10-19T14:00:00Z', '2026-10-19T22:00:00Z', 480))).toBeNull();
    expect(reason(sleep('2026-10-19T14:00:00Z', '2026-10-19T22:00:00Z', 481))).toBe(
      '수면 시간(sleepMinutes)이 시작~종료 구간 길이보다 깁니다.',
    );
    // 서울 10/19 07:00 기상을 date=10/20 으로: 깬 날짜가 date-1 이라 허용(기기 시간대 차이)
    expect(reason(sleep('2026-10-18T15:30:00Z', '2026-10-18T22:00:00Z', 390), '2026-10-20')).toBeNull();
    // 날짜 창(전날 00:00~다음 날 24:00) 밖이면 창 위반이 먼저다
    expect(reason(sleep('2026-10-18T14:00:00Z', '2026-10-18T22:00:00Z', 480), '2026-10-20')).toBe(
      '활동 시간이 동기화 날짜 범위를 벗어났습니다.',
    );
  });

  it('영어 문구(MessageFormat choice·숫자 서식)', () => {
    const r = validator.rejection(date, payload({ type: 'STEPS', steps: 100_001 }))!;
    expect(resolve('en', r)).toBe('Steps must be between 0 and 100,000.');
  });
});
