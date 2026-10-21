import { expect, it } from 'vitest';
import { describeDb } from '../support/api.js';
import { signUp, stepsBody, useApi, workoutAt, type Call } from '../support/flow.js';

/** 동기화 규칙(#107)을 HTTP → DB 까지 통과시켜 확인한다. 시계는 서울 2026-10-20 12:00 로 고정 */
describeDb('동기화 규칙(PostgreSQL)', () => {
  const api = useApi({ env: { APP_RATE_LIMIT_SYNC_PER_MINUTE: '0' } });

  async function start() {
    const { call } = api.build();
    const session = await signUp(call);
    const sync = (body: unknown, headers?: Record<string, string>) =>
      call('POST', '/api/health-activities/sync', { token: session.accessToken, body, headers });
    const character = async () => (await call('GET', '/api/characters/me', { token: session.accessToken })).json.character;
    return { call: call as Call, session, sync, character };
  }
  const sleep = (minutes: number, extra: Record<string, unknown> = {}, endHour = 22) => ({
    type: 'SLEEP',
    sleepMinutes: minutes,
    startedAt: new Date(Date.parse(`2026-10-19T${endHour}:00:00Z`) - minutes * 60_000).toISOString().replace('.000Z', 'Z'),
    endedAt: `2026-10-19T${endHour}:00:00Z`,
    ...extra,
  });

  it('걸음 수 능력치: 처음 지급 DISCIPLINE +1, 목표(8,000) 달성 전환 때 +1 한 번만', async () => {
    const { sync, character } = await start();
    await sync(stepsBody(2000));
    expect((await character()).stats.discipline).toBe(1);
    await sync(stepsBody(4000));
    expect((await character()).stats.discipline).toBe(1); // 이미 기본 지급
    await sync(stepsBody(8500));
    expect((await character()).stats.discipline).toBe(2);
    await sync(stepsBody(20_000));
    expect((await character()).stats.discipline).toBe(2); // 목표는 한 번만
  });

  it('같은 요청의 걸음 수가 여럿이면 가장 큰 값 하나만 반영하고 나머지는 중복 처리', async () => {
    const { sync } = await start();
    const res = await sync({ date: '2026-10-20', activities: [{ type: 'STEPS', steps: 1000 }, { type: 'STEPS', steps: 9000 }, { type: 'STEPS', steps: 5000 }] });
    expect(res.json.gainedXp).toBe(75);
    expect(res.json.activityResults.map((r: { duplicate: boolean }) => r.duplicate)).toEqual([true, false, true]);
    expect(res.json.goals[0]).toMatchObject({ current: 9000, achieved: true });
  });

  it('범위를 벗어난 항목은 그 항목만 건너뛰고(사유 포함) 나머지는 처리된다', async () => {
    const { sync } = await start();
    const res = await sync({
      date: '2026-10-20',
      activities: [{ type: 'STEPS', steps: 100_001 }, { type: 'STEPS', steps: 100 }, workoutAt(0, { minutes: 30, calories: 99_999 })],
    });
    expect(res.status).toBe(200);
    expect(res.json.activityResults[0]).toMatchObject({ duplicate: true, gainedXp: 0 });
    expect(res.json.activityResults[0].message).toContain('걸음 수는 0 이상 100000 이하여야 합니다');
    expect(res.json.activityResults[2].message).toContain('칼로리');
    // 범위 위반 항목이 있어도 유효한 최대값 하나(100)만 반영
    expect(res.json.activityResults[1].gainedXp).toBe(0); // 100보는 0 XP 지만 기록은 됨
    expect(res.json.goals[0].current).toBe(100);
  });

  it('수면: 더 긴 수면으로 갱신하면 XP 차액과 품질 달성 INT +1, 짧거나 같은 값은 중복', async () => {
    const { sync, character } = await start();
    const first = await sync({ date: '2026-10-20', activities: [sleep(330)] }); // 점수 50 → 품질 보너스 없음, 기본 25
    expect(first.json.gainedXp).toBe(25);
    expect(await character()).toMatchObject({ stats: { recovery: 1, intStat: 1 } });
    const longer = await sync({ date: '2026-10-20', activities: [sleep(450)] }); // 기본 60 + 보너스 20 = 80 → 차액 55
    expect(longer.json.gainedXp).toBe(55);
    expect(await character()).toMatchObject({ stats: { recovery: 1, intStat: 2 } }); // 품질 달성 전환 INT +1
    expect((await sync({ date: '2026-10-20', activities: [sleep(450)] })).json.activityResults[0].duplicate).toBe(true);
    expect((await sync({ date: '2026-10-20', activities: [sleep(400)] })).json.activityResults[0].duplicate).toBe(true);
    expect((await character()).stats).toMatchObject({ recovery: 1, intStat: 2 });
  });

  it('겹치는 운동은 먼저 반영된 것만, 맞닿는 운동은 둘 다 반영', async () => {
    const { sync } = await start();
    const a = await sync({ date: '2026-10-20', activities: [workoutAt(0, { minutes: 30 })] });
    expect(a.json.gainedXp).toBeGreaterThan(0);
    const overlap = await sync({ date: '2026-10-20', activities: [workoutAt(20, { minutes: 30, type: 'RUNNING' })] });
    expect(overlap.json.activityResults[0]).toMatchObject({ duplicate: true, gainedXp: 0 });
    expect(overlap.json.activityResults[0].message).toContain('겹');
    const adjacent = await sync({ date: '2026-10-20', activities: [workoutAt(30, { minutes: 30, type: 'WALKING' })] });
    expect(adjacent.json.gainedXp).toBeGreaterThan(0);
  });

  it('5분 미만 운동은 겹침 판정에서 빠져 같은 시간대의 실제 운동을 막지 않는다', async () => {
    const { sync } = await start();
    const short = await sync({ date: '2026-10-20', activities: [workoutAt(10, { minutes: 4, type: 'SWIMMING', calories: 10 })] });
    expect(short.json.gainedXp).toBe(0);
    expect(short.json.activityResults[0].duplicate).toBe(false);
    const real = await sync({ date: '2026-10-20', activities: [workoutAt(0, { minutes: 30 })] });
    expect(real.json.gainedXp).toBeGreaterThan(0);
    // 같은 짧은 운동을 다시 보내면 중복
    expect((await sync({ date: '2026-10-20', activities: [workoutAt(10, { minutes: 4, type: 'SWIMMING', calories: 10 })] })).json.activityResults[0].duplicate).toBe(true);
  });

  it('운동 XP 일일 상한은 시작 시각의 현지 날짜로 센다: 요청 date 를 바꿔 나눠 보내도 우회 불가', async () => {
    const { sync } = await start();
    // 서울 10/20 에 시작하는 200 XP 운동 3개를 date=10/19, 10/20, 10/21 로 나눠 보낸다
    const results = [];
    for (const [i, date] of ['2026-10-19', '2026-10-20', '2026-10-21'].entries()) {
      results.push(await sync({ date, activities: [workoutAt(i * 70, { calories: 600 })] }));
    }
    expect(results.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(results.reduce((acc, r) => acc + r.json.gainedXp, 0)).toBe(400);
  });

  it('일일 총 상한 600: 걸음 100 + 수면 80 + 운동 400 이 모두 차면 더는 지급하지 않고 "상한" 문구', async () => {
    const { sync, character } = await start();
    const res = await sync({
      date: '2026-10-20',
      activities: [{ type: 'STEPS', steps: 50_000 }, sleep(480), workoutAt(0, { calories: 600 }), workoutAt(70, { calories: 600 }), workoutAt(140, { calories: 600 })],
    });
    expect(res.json.gainedXp).toBe(100 + 80 + 200 + 200);
    const third = res.json.activityResults[4];
    expect(third).toMatchObject({ gainedXp: 0, duplicate: false });
    expect(third.message).toContain('상한');
    const more = await sync(stepsBody(60_000));
    expect(more.json.gainedXp).toBe(0);
    expect((await character()).totalXp).toBe(580);
  });

  it('운동 상한(400)이 찬 뒤에도 걸음·수면은 총 상한 안에서 지급하고, 더 못 받는 운동은 영어 문구에 cap 표시', async () => {
    const { sync } = await start();
    await sync({ date: '2026-10-20', activities: [workoutAt(0, { calories: 600 }), workoutAt(70, { calories: 600 })] }); // 운동 400
    const partial = await sync({ date: '2026-10-20', activities: [{ type: 'STEPS', steps: 50_000 }, sleep(480)] }, { 'Accept-Language': 'en' });
    expect(partial.json.gainedXp).toBe(180); // 100 + 80: 총 상한 600 안
    const none = await sync({ date: '2026-10-20', activities: [workoutAt(300, { calories: 600 })] }, { 'Accept-Language': 'en' });
    expect(none.json.gainedXp).toBe(0);
    expect(none.json.activityResults[0].message).toMatch(/cap/i);
  });

  it('다른 날짜로 같은 수면을 다시 보내면 건너뛰지만, 더 늦게 끝난 수면은 새 수면으로 받는다', async () => {
    const { sync } = await start();
    await sync({ date: '2026-10-20', activities: [sleep(480)] });
    const sameOtherDate = await sync({ date: '2026-10-19', activities: [sleep(480)] });
    expect(sameOtherDate.json.activityResults[0]).toMatchObject({ duplicate: true, gainedXp: 0 });
    const laterWake = await sync({ date: '2026-10-19', activities: [sleep(500, {}, 23)] });
    expect(laterWake.json.activityResults[0].duplicate).toBe(false);
  });

  it('목표 현황: 걸음 최대값, 운동 분 합계(5분 미만 포함), 수면 최대값', async () => {
    const { sync } = await start();
    const res = await sync({
      date: '2026-10-20',
      activities: [{ type: 'STEPS', steps: 4000 }, workoutAt(0, { minutes: 20 }), workoutAt(30, { minutes: 4, type: 'OTHER', calories: 5 }), sleep(300)],
    });
    expect(res.json.goals).toEqual([
      { type: 'STEPS', target: 8000, current: 4000, unit: 'steps', achieved: false },
      { type: 'WORKOUT', target: 30, current: 24, unit: 'minutes', achieved: false },
      { type: 'SLEEP', target: 420, current: 300, unit: 'minutes', achieved: false },
    ]);
  });

  it('요청 구조 오류는 아무것도 저장하지 않고 400, 존재하지 않던 사용자의 캐릭터도 만들지 않는다', async () => {
    const { sync, call, session } = await start();
    const bad = await sync({ date: '2026-10-25', activities: [{ type: 'STEPS', steps: 1 }] });
    expect(bad.status).toBe(400);
    expect(bad.json.message).toBe('동기화 날짜는 오늘 기준 ±1일 이내여야 합니다.');
    expect((await call('GET', '/api/characters/me', { token: session.accessToken })).status).toBe(404);
    expect(await api.db`select 1 from health_activity_log`).toHaveLength(0);
  });

  it('운동 durationMinutes 를 생략하면 시작~종료 구간으로 채운다', async () => {
    const { sync, call, session } = await start();
    await sync({ date: '2026-10-20', activities: [workoutAt(0, { minutes: 45, type: 'WALKING', calories: 0 })] });
    const daily = await call('GET', '/api/health-activities?date=2026-10-20', { token: session.accessToken });
    expect(daily.json.activities[0]).toMatchObject({ durationMinutes: 45, gainedXp: 45 * 3 + 5 });
  });
});
