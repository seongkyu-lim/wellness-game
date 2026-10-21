import { expect, it } from 'vitest';
import { refreshTokens } from '../../src/auth/repositories.js';
import { randomUuid, sha256Hex } from '../../src/lib/crypto.js';
import { Instant } from '../../src/lib/instant.js';
import { describeDb } from '../support/api.js';
import { signUp, stepsBody, useApi, workoutAt, type Call } from '../support/flow.js';

/**
 * 같은 사용자·같은 토큰에 대한 동시 요청. 인메모리 상태가 아니라 DB 잠금(캐릭터 행, 리프레시 family)으로 지켜야 하는 불변식이다.
 * 풀 크기(4)보다 많은 요청을 한꺼번에 보내 잠금 대기 중에도 진행되는지 함께 본다.
 */
describeDb('동시성(PostgreSQL)', () => {
  const api = useApi({ env: { APP_RATE_LIMIT_SYNC_PER_MINUTE: '0', APP_RATE_LIMIT_AUTH_PER_MINUTE: '0', APP_RATE_LIMIT_LOGIN_PER_MINUTE: '0' } });

  const sync = (call: Call, token: string, body: unknown) => call('POST', '/api/health-activities/sync', { token, body });

  /** 사용자 한 명의 불변식: 캐릭터 XP = 로그 XP 합계, 날짜별 상한, 로그 중복 없음 */
  async function assertConsistent(userId: string) {
    const [character] = await api.db<{ total_xp: number; current_xp: number; level: number }[]>`
      select total_xp, current_xp, level from user_character where user_id = ${userId}`;
    const logs = await api.db<{ activity_date: string; type: string; gained_xp: number; external_key: string }[]>`
      select activity_date, type, gained_xp, external_key from health_activity_log where user_id = ${userId}`;
    expect(character).toBeDefined();
    const sum = logs.reduce((acc, l) => acc + l.gained_xp, 0);
    expect(character!.total_xp, '캐릭터 XP 와 로그 XP 합계').toBe(sum);
    expect(new Set(logs.map((l) => l.external_key)).size, '외부 키 중복 없음').toBe(logs.length);
    const perDay = new Map<string, { all: number; workout: number }>();
    for (const l of logs) {
      const d = perDay.get(l.activity_date) ?? { all: 0, workout: 0 };
      d.all += l.gained_xp;
      if (l.type === 'WORKOUT') d.workout += l.gained_xp;
      perDay.set(l.activity_date, d);
    }
    for (const [date, d] of perDay) {
      expect(d.all, `${date} 일일 상한 600`).toBeLessThanOrEqual(600);
      expect(d.workout, `${date} 운동 상한 400`).toBeLessThanOrEqual(400);
    }
    return { character: character!, logs, perDay };
  }

  it('같은 요청을 12번 동시에 보내도 XP 는 한 번만 지급된다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const body = { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 8000 }, workoutAt(0, { minutes: 30, type: 'RUNNING', calories: 300 })] };
    const results = await Promise.all(Array.from({ length: 12 }, () => sync(call, session.accessToken, body)));
    expect(results.every((r) => r.status === 200)).toBe(true);
    const gained = results.map((r) => r.json.gainedXp as number);
    expect(gained.filter((g) => g > 0)).toHaveLength(1);
    expect(gained.reduce((a, b) => a + b, 0)).toBe(70 + 135);
    const { character, logs } = await assertConsistent(session.userId);
    expect(character.total_xp).toBe(205);
    expect(logs).toHaveLength(2);
  });

  it('겹치지 않는 운동 6개(각 200 XP)를 별도 요청으로 동시에 보내면 운동 상한 400 에서 멈춘다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const results = await Promise.all(
      Array.from({ length: 6 }, (_, i) =>
        sync(call, session.accessToken, { date: '2026-10-20', activities: [workoutAt(i * 70, { calories: 600, type: 'CYCLING' })] }),
      ),
    );
    expect(results.every((r) => r.status === 200)).toBe(true);
    const total = results.reduce((acc, r) => acc + (r.json.gainedXp as number), 0);
    expect(total).toBe(400);
    // 상한에 걸려 0 XP 가 된 운동 4건은 스탯도 없다(#107): 스탯은 지급된 운동 2건분
    const { character, perDay } = await assertConsistent(session.userId);
    expect(character.total_xp).toBe(400);
    expect([...perDay.values()]).toEqual([{ all: 400, workout: 400 }]);
    const [stats] = await api.db<{ str: number; vit: number }[]>`select str, vit from user_character where user_id = ${session.userId}`;
    expect(stats).toEqual({ str: 2, vit: 4 }); // 지급된 CYCLING 2건 × (vit +2, str +1)
  });

  it('걸음·수면·운동이 섞인 서로 다른 요청을 동시에 보내도 일일 상한 600 과 XP 합계 일치가 지켜진다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const sleep = { type: 'SLEEP', sleepMinutes: 480, startedAt: '2026-10-19T14:00:00Z', endedAt: '2026-10-19T22:00:00Z' };
    const requests = [
      { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 12_000 }] },
      { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 30_000 }] },
      { date: '2026-10-20', activities: [sleep] },
      { date: '2026-10-20', activities: [workoutAt(0, { calories: 600 })] },
      { date: '2026-10-20', activities: [workoutAt(70, { calories: 600 })] },
      { date: '2026-10-20', activities: [workoutAt(140, { calories: 600 })] },
      { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 50_000 }, sleep] },
    ];
    const results = await Promise.all(requests.map((body) => sync(call, session.accessToken, body)));
    expect(results.every((r) => r.status === 200)).toBe(true);
    const { perDay, character } = await assertConsistent(session.userId);
    const day = perDay.get('2026-10-20')!;
    expect(day.workout).toBe(400);
    expect(day.all).toBeLessThanOrEqual(600);
    // 응답으로 받은 gainedXp 합계도 저장된 값과 같다(이중 지급 없음)
    expect(results.reduce((acc, r) => acc + (r.json.gainedXp as number), 0)).toBe(character.total_xp);
    // 걸음은 가장 큰 값(50,000 → 100XP) 하나, 수면은 한 건
    const logs = await api.db<{ type: string; steps: number | null }[]>`select type, steps from health_activity_log where user_id = ${session.userId} and type <> 'WORKOUT'`;
    expect(logs.filter((l) => l.type === 'STEPS')).toHaveLength(1);
    expect(logs.filter((l) => l.type === 'SLEEP')).toHaveLength(1);
    expect(logs.find((l) => l.type === 'STEPS')!.steps).toBe(50_000);
  });

  it('겹치는 운동을 동시에 보내면 먼저 반영된 하나만 남는다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const results = await Promise.all(
      Array.from({ length: 5 }, (_, i) =>
        sync(call, session.accessToken, { date: '2026-10-20', activities: [workoutAt(i * 10, { minutes: 60, calories: 100 })] }),
      ),
    );
    expect(results.filter((r) => r.json.gainedXp > 0)).toHaveLength(1);
    const logs = await api.db`select 1 from health_activity_log where user_id = ${session.userId}`;
    expect(logs).toHaveLength(1);
    await assertConsistent(session.userId);
  });

  it('첫 동기화(캐릭터 없음)를 같은 사용자가 동시에 보내도 캐릭터는 하나', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    const results = await Promise.all(Array.from({ length: 8 }, (_, i) => sync(call, session.accessToken, stepsBody(1000 + i))));
    expect(results.every((r) => r.status === 200)).toBe(true);
    const characters = await api.db`select 1 from user_character where user_id = ${session.userId}`;
    expect(characters).toHaveLength(1);
    await assertConsistent(session.userId);
  });

  it('서로 다른 사용자의 동기화는 서로 영향을 주지 않는다', async () => {
    const { call } = api.build();
    const users = await Promise.all(['user_aa', 'user_bb', 'user_cc'].map((name) => signUp(call, name)));
    await Promise.all(
      users.flatMap((u) => [
        sync(call, u.accessToken, { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 8000 }, workoutAt(0)] }),
        sync(call, u.accessToken, { date: '2026-10-20', activities: [{ type: 'STEPS', steps: 8000 }, workoutAt(0)] }),
      ]),
    );
    const totals = await api.db<{ total_xp: number }[]>`select total_xp from user_character order by user_id`;
    expect(totals).toHaveLength(3);
    expect(new Set(totals.map((t) => t.total_xp)).size).toBe(1);
    for (const u of users) await assertConsistent(u.userId);
  });

  it('같은 리프레시 토큰을 동시에 보내도 활성 후계 토큰이 가족당 최대 2개(회전 1 + 유예 형제 1)', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    api.clock.advance(1000);
    const results = await Promise.all(
      Array.from({ length: 10 }, () => call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } })),
    );
    const ok = results.filter((r) => r.status === 200);
    expect(ok).toHaveLength(2);
    expect(new Set(ok.map((r) => r.json.refreshToken)).size).toBe(2);
    // 재사용 감지가 family 를 폐기했으므로 이 두 토큰도 죽었다
    const [live] = await api.db<{ n: number }[]>`select count(*)::int as n from refresh_token where revoked_at is null`;
    expect(live!.n).toBe(0);
  });

  it('이 기기 로그아웃과 회전이 경쟁해도 어느 순서든 family 에 살아 있는 토큰이 남지 않는다', async () => {
    const { call } = api.build();
    for (let round = 0; round < 12; round++) {
      const session = await signUp(call, `race_user_${round}`);
      api.clock.advance(1000);
      const logoutFirst = round % 2 === 0;
      const rotate = () => call('POST', '/api/auth/refresh', { body: { refreshToken: session.refreshToken } });
      const logout = () => call('POST', '/api/auth/logout', { body: { refreshToken: session.refreshToken } });
      const [a, b] = await Promise.all(logoutFirst ? [logout(), rotate()] : [rotate(), logout()]);
      const statuses = [a.status, b.status].sort();
      // 로그아웃은 항상 204, 회전은 (회전이 먼저면) 200 또는 (로그아웃이 먼저면) 401
      expect(statuses[0] === 204 || statuses[1] === 204).toBe(true);
      const [live] = await api.db<{ n: number }[]>`select count(*)::int as n from refresh_token where user_id = ${session.userId} and revoked_at is null`;
      expect(live!.n, `round ${round}: 로그아웃 뒤 살아 있는 토큰`).toBe(0);
      // 회전으로 받은 토큰이 있어도 쓸 수 없다
      const rotated = [a, b].find((r) => r.status === 200);
      if (rotated) expect((await call('POST', '/api/auth/refresh', { body: { refreshToken: rotated.json.refreshToken } })).status).toBe(401);
    }
  });

  it('로그아웃과 로그아웃-전체가 동시에 와도 둘 다 성공하고 토큰이 모두 폐기된다', async () => {
    const { call } = api.build();
    const phone = await signUp(call);
    const tablet = (await call('POST', '/api/auth/login', { body: { username: 'tester_01', password: 'password123' } })).json;
    api.clock.advance(1000);
    const results = await Promise.all([
      call('POST', '/api/auth/logout', { body: { refreshToken: phone.refreshToken } }),
      call('POST', '/api/auth/logout-all', { token: tablet.accessToken }),
      call('POST', '/api/auth/logout', { body: { refreshToken: tablet.refreshToken } }),
    ]);
    expect(results.map((r) => r.status)).toEqual([204, 204, 204]);
    const [live] = await api.db<{ n: number }[]>`select count(*)::int as n from refresh_token where revoked_at is null`;
    expect(live!.n).toBe(0);
  });

  /**
   * 알려진 결함(보고용): revokeAll 은 family 를 잠그지 않고 `update ... where user_id = ? and revoked_at is null` 만 실행한다.
   * READ COMMITTED 에서 이 UPDATE 는 시작 시점의 스냅샷만 보므로, 같은 순간 커밋되는 회전이 새로 만든 토큰은 폐기되지 않는다.
   * 로그아웃-전체 뒤에도 (회전을 먼저 시작한 쪽의) 리프레시 토큰이 살아 남는다. 수정되면 이 테스트가 "통과"해서 실패하므로
   * `it.fails` 를 `it` 로 바꾸면 된다. 타이밍에 기대지 않도록 회전의 커밋을 테스트가 직접 쥐고 있다가 푼다.
   */
  it.fails('[알려진 결함] 로그아웃-전체가 진행 중인 회전이 만든 새 리프레시 토큰을 폐기하지 못한다', async () => {
    const { call } = api.build();
    const session = await signUp(call);
    api.clock.advance(1000);
    const old = (await refreshTokens.findByHash(api.db, await sha256Hex(session.refreshToken)))!;
    const now = Instant.ofEpochMilli(api.clock.now);

    let logoutAll!: ReturnType<Call>;
    await api.db.begin(async (tx) => {
      // RefreshTokenService.rotate 와 같은 문장(family 잠금 → 새 토큰 삽입 → 옛 토큰 폐기)을 커밋하지 않은 채 실행한다.
      // 옛 토큰 행은 잠기고, 새 토큰 행은 다른 세션에 보이지 않는다
      const db = tx as unknown as typeof api.db;
      const successorId = randomUuid();
      await refreshTokens.lockFamily(db, old.family_id);
      await refreshTokens.insert(db, {
        id: successorId,
        userId: old.user_id,
        familyId: old.family_id,
        familyCreatedAt: old.family_created_at,
        tokenHash: await sha256Hex('successor-token'),
        issuedAt: now,
        expiresAt: Instant.ofEpochMilli(api.clock.now + 30 * 86_400_000),
        scope: 'full',
      });
      await refreshTokens.markRotated(db, old.id, now, successorId);
      logoutAll = call('POST', '/api/auth/logout-all', { token: session.accessToken });
      // 로그아웃-전체의 UPDATE 가 옛 토큰 행의 잠금을 기다리는 것이 보일 때까지 기다린다(조건 대기, 고정 시간 아님)
      for (let i = 0; i < 200; i++) {
        const waiting = await api.db`select 1 from pg_stat_activity where wait_event_type = 'Lock' and query ilike '%update refresh_token%'`;
        if (waiting.length > 0) return;
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
      throw new Error('로그아웃-전체가 잠금 대기 상태에 들어가지 않았습니다.');
    });
    expect((await logoutAll).status).toBe(204);
    const [live] = await api.db<{ n: number }[]>`select count(*)::int as n from refresh_token where revoked_at is null`;
    expect(live!.n, '로그아웃-전체 뒤 살아 있는 리프레시 토큰').toBe(0);
  });

  it('동시 가입: 같은 아이디(대소문자만 다름)는 한 건만 성공', async () => {
    const { call } = api.build();
    const results = await Promise.all(
      ['dupe_user', 'DUPE_USER', 'Dupe_User', 'dupe_user'].map((username) =>
        call('POST', '/api/auth/signup', { body: { username, password: 'password123' } }),
      ),
    );
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(results.filter((r) => r.status === 400 && r.json.message === '이미 사용 중인 아이디입니다.')).toHaveLength(3);
    const rows = await api.db`select 1 from user_credential`;
    expect(rows).toHaveLength(1);
  });
});
