import type { Hono } from 'hono';
import { ACTIVITY_TYPES, WORKOUT_TYPES, type ActivityPayload } from '../activity/types.js';
import { MAX_ACTIVITIES_PER_REQUEST } from '../activity/validator.js';
import { healthActivities } from '../activity/repository.js';
import { characterResponse, characters } from '../character/character.js';
import {
  decimal,
  enumOf,
  instant,
  int,
  localDate,
  notEmpty,
  notNull,
  objectList,
  positiveOrZero,
  readJsonObject,
  size,
  str,
  validate,
  type Check,
} from '../http/body.js';
import { authenticatedUserId, type AppContext, type AppEnv, type Platform } from '../http/env.js';
import { parseLocalDate } from '../lib/dates.js';
import { forbidden, invalidParameter, missingParameter } from '../lib/errors.js';

/**
 * 요청 userId(body/경로/쿼리)를 토큰 주체와 대조한다(Spring UserAccess).
 * 없으면(null·공백) 토큰 주체, 있으면 주체와 같을 때만 그 값. 다르면 403
 */
export function resolveUserAccess(authenticatedUserId: string, requestedUserId: string | null | undefined): string {
  if (!requestedUserId || !requestedUserId.trim()) return authenticatedUserId;
  if (requestedUserId !== authenticatedUserId) throw forbidden('auth.forbidden');
  return authenticatedUserId;
}

/** 캐릭터 조회·건강 활동 동기화·일별 조회(Spring CharacterController, HealthActivityController) */
export function registerGameRoutes(app: Hono<AppEnv>, platform: Platform) {
  async function characterSummary(c: AppContext, userId: string) {
    const character = await characters.findByUserId(platform.db(c), userId);
    if (!character) return c.body(null, 404);
    return c.json({ userId: character.userId, character: characterResponse(character) });
  }

  /** 토큰 주체의 캐릭터. 응답 형식은 /{userId} 와 같다 */
  app.get('/api/characters/me', (c) => characterSummary(c, authenticatedUserId(c)));

  app.get('/api/characters/:userId', (c) =>
    characterSummary(c, resolveUserAccess(authenticatedUserId(c), c.req.param('userId'))),
  );

  app.post('/api/health-activities/sync', async (c) => {
    const userId = authenticatedUserId(c);
    const body = await readJsonObject(c);
    const requestedUserId = str(body, 'userId');
    const date = localDate(body, 'date');
    const rawActivities = objectList(body, 'activities');
    const activities = rawActivities?.map((item) => decodeActivity(item)) ?? null;
    const checks: Check[] = [
      notNull('date', date),
      notEmpty('activities', activities),
      size('activities', activities, 0, MAX_ACTIVITIES_PER_REQUEST, 'validation.activities.size'),
    ];
    activities?.forEach((a, i) => {
      const field = (name: string) => `activities[${i}].${name}`;
      checks.push(
        notNull(field('type'), a.type),
        positiveOrZero(field('durationMinutes'), a.durationMinutes),
        positiveOrZero(field('calories'), a.calories),
        positiveOrZero(field('distanceMeters'), a.distanceMeters),
        positiveOrZero(field('steps'), a.steps),
        positiveOrZero(field('sleepMinutes'), a.sleepMinutes),
        positiveOrZero(field('sleepScore'), a.sleepScore),
      );
    });
    validate(...checks);
    const resolvedUserId = resolveUserAccess(userId, requestedUserId);
    const response = await c.get('services').sync.sync(
      platform.db(c),
      { userId: resolvedUserId, date: date!, activities: activities as ActivityPayload[] },
      c.get('locale'),
    );
    return c.json(response);
  });

  app.get('/api/health-activities', async (c) => {
    const userId = authenticatedUserId(c);
    const requestedUserId = c.req.query('userId');
    const rawDate = c.req.query('date');
    if (rawDate === undefined || rawDate.trim() === '') throw missingParameter();
    const date = parseLocalDate(rawDate.trim());
    if (!date) throw invalidParameter();
    const resolvedUserId = resolveUserAccess(userId, requestedUserId);
    const rows = await healthActivities.findByUserIdAndDate(platform.db(c), resolvedUserId, date);
    return c.json({
      userId: resolvedUserId,
      date,
      activities: rows.map((a) => ({
        type: a.type,
        source: a.source_type,
        durationMinutes: a.duration_minutes,
        calories: a.calories,
        distanceMeters: a.distance_meters,
        steps: a.steps,
        sleepMinutes: a.sleep_minutes,
        sleepScore: a.sleep_score,
        gainedXp: a.gained_xp,
        startedAt: a.started_at,
        endedAt: a.ended_at,
      })),
    });
  });
}

/** activities[] 항목 역직렬화(형식 오류는 400 error.unreadable-body). type 누락은 제약 검증에서 걸린다 */
function decodeActivity(item: Record<string, unknown>): Omit<ActivityPayload, 'type'> & { type: ActivityPayload['type'] | null } {
  return {
    type: enumOf(item, 'type', ACTIVITY_TYPES),
    workoutType: enumOf(item, 'workoutType', WORKOUT_TYPES),
    durationMinutes: int(item, 'durationMinutes'),
    calories: decimal(item, 'calories'),
    distanceMeters: decimal(item, 'distanceMeters'),
    steps: int(item, 'steps'),
    sleepMinutes: int(item, 'sleepMinutes'),
    sleepScore: int(item, 'sleepScore'),
    startedAt: instant(item, 'startedAt'),
    endedAt: instant(item, 'endedAt'),
  };
}
