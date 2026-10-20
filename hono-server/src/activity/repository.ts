import type { Db } from '../db/client.js';
import type { LocalDate } from '../lib/dates.js';
import type { Instant } from '../lib/instant.js';
import type { ActivityType } from './types.js';

export interface HealthActivityRow {
  id: string;
  user_id: string;
  activity_date: LocalDate;
  type: ActivityType;
  source_type: string | null;
  duration_minutes: number | null;
  calories: number | null;
  distance_meters: number | null;
  steps: number | null;
  sleep_minutes: number | null;
  sleep_score: number | null;
  gained_xp: number;
  external_key: string;
  started_at: Instant | null;
  ended_at: Instant | null;
}

export interface NewHealthActivity {
  id: string;
  userId: string;
  activityDate: LocalDate;
  type: ActivityType;
  sourceType: string;
  durationMinutes: number | null;
  calories: number;
  distanceMeters: number;
  steps: number | null;
  sleepMinutes: number | null;
  sleepScore: number | null;
  gainedXp: number;
  externalKey: string;
  startedAt: Instant | null;
  endedAt: Instant | null;
  createdAt: Instant;
}

const COLUMNS = `id::text, user_id, activity_date, type, source_type, duration_minutes, calories, distance_meters, steps,
  sleep_minutes, sleep_score, gained_xp, external_key, started_at, ended_at`;

const ts = (value: Instant | null) => (value === null ? null : value.toString());

/** health_activity_log SQL(Spring HealthActivityRepository) */
export const healthActivities = {
  async existsByExternalKey(db: Db, externalKey: string): Promise<boolean> {
    const rows = await db`select 1 from health_activity_log where external_key = ${externalKey} limit 1`;
    return rows.length > 0;
  },

  async findByExternalKey(db: Db, externalKey: string): Promise<HealthActivityRow | undefined> {
    const rows = await db.unsafe<HealthActivityRow[]>(`select ${COLUMNS} from health_activity_log where external_key = $1`, [externalKey]);
    return rows[0];
  },

  /** 시작 시각 오름차순(null 먼저). 같은 시작 시각은 생성 순 */
  async findByUserIdAndDate(db: Db, userId: string, date: LocalDate): Promise<HealthActivityRow[]> {
    return db.unsafe<HealthActivityRow[]>(
      `select ${COLUMNS} from health_activity_log where user_id = $1 and activity_date = $2::date
       order by started_at asc nulls first, created_at asc, id asc`,
      [userId, date],
    );
  },

  /**
   * 같은 사용자·유형의 기존 로그 중 [startedAt, endedAt) 와 겹치는 것들. 경계가 맞닿는 것은 겹침이 아니다.
   * 어떤 겹침을 막을지(5분 미만 운동 제외, 수면은 다른 날짜 로그만 등)는 서비스가 고른다.
   */
  async findOverlapping(db: Db, userId: string, type: ActivityType, startedAt: Instant, endedAt: Instant): Promise<HealthActivityRow[]> {
    return db.unsafe<HealthActivityRow[]>(
      `select ${COLUMNS} from health_activity_log
       where user_id = $1 and type = $2 and started_at < $3::timestamptz and ended_at > $4::timestamptz`,
      [userId, type, endedAt.toString(), startedAt.toString()],
    );
  },

  async sumGainedXp(db: Db, userId: string, date: LocalDate, type?: ActivityType): Promise<number> {
    const rows = type
      ? await db<{ total: string }[]>`
          select coalesce(sum(gained_xp), 0)::text as total from health_activity_log
          where user_id = ${userId} and activity_date = ${date}::date and type = ${type}`
      : await db<{ total: string }[]>`
          select coalesce(sum(gained_xp), 0)::text as total from health_activity_log
          where user_id = ${userId} and activity_date = ${date}::date`;
    return Number(rows[0]?.total ?? 0);
  },

  async insert(db: Db, a: NewHealthActivity): Promise<void> {
    await db`
      insert into health_activity_log (id, user_id, activity_date, type, source_type, duration_minutes, calories, distance_meters,
                                       steps, sleep_minutes, sleep_score, gained_xp, external_key, started_at, ended_at, created_at)
      values (${a.id}::uuid, ${a.userId}, ${a.activityDate}::date, ${a.type}, ${a.sourceType}, ${a.durationMinutes},
              ${a.calories}, ${a.distanceMeters}, ${a.steps}, ${a.sleepMinutes}, ${a.sleepScore}, ${a.gainedXp},
              ${a.externalKey}, ${ts(a.startedAt)}::timestamptz, ${ts(a.endedAt)}::timestamptz, ${a.createdAt.toString()}::timestamptz)`;
  },

  async updateSteps(db: Db, id: string, steps: number, gainedXp: number): Promise<void> {
    await db`update health_activity_log set steps = ${steps}, gained_xp = ${gainedXp} where id = ${id}::uuid`;
  },

  async updateSleep(
    db: Db,
    id: string,
    s: { sleepMinutes: number; sleepScore: number | null; startedAt: Instant | null; endedAt: Instant | null; gainedXp: number },
  ): Promise<void> {
    await db`
      update health_activity_log
      set sleep_minutes = ${s.sleepMinutes}, sleep_score = ${s.sleepScore}, started_at = ${ts(s.startedAt)}::timestamptz,
          ended_at = ${ts(s.endedAt)}::timestamptz, gained_xp = ${s.gainedXp}
      where id = ${id}::uuid`;
  },
};
