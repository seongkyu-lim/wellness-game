import { characterResponse, characters, saturatedAdd, type CharacterStats, type UserCharacter } from '../character/character.js';
import { transaction, type Db } from '../db/client.js';
import { randomUuid } from '../lib/crypto.js';
import { localDateOf, type LocalDate } from '../lib/dates.js';
import { resolve, type LocalizedMessage } from '../lib/errors.js';
import { message, type Locale } from '../lib/i18n.js';
import { Instant } from '../lib/instant.js';
import { healthActivities } from './repository.js';
import type { ActivityPayload, ActivityType, SyncRequest } from './types.js';
import { spanMinutes, type HealthActivitySyncValidator } from './validator.js';
import {
  DAILY_WORKOUT_XP_CAP,
  DAILY_XP_CAP,
  MIN_WORKOUT_MINUTES,
  SLEEP_GOAL_MINUTES,
  SLEEP_QUALITY_BONUS_SCORE,
  STEPS_GOAL,
  WORKOUT_GOAL_MINUTES,
  calculateXp,
  resolvedSleepScore,
  sleepXp,
  stepsXp,
} from './xp-calculator.js';

export interface ActivityResult {
  type: ActivityType;
  source: string;
  gainedXp: number;
  message: string;
  duplicate: boolean;
}

export interface Goal {
  type: ActivityType;
  target: number;
  current: number;
  unit: string;
  achieved: boolean;
}

export interface SyncResponse {
  userId: string;
  date: LocalDate;
  gainedXp: number;
  levelUp: boolean;
  character: ReturnType<typeof characterResponse>;
  activityResults: ActivityResult[];
  goals: Goal[];
}

type StatDelta = Partial<CharacterStats>;

/**
 * 활동 하나의 처리 결과. applied 일 때 earnedXp 는 일일 상한(총·유형별) 적용 전 XP, stats 는 XP 가 실제 지급될 때만 반영할 능력치.
 * tooShort 는 최소 운동 시간 미만 운동(기록만, XP·스탯 없음), sleepOverlap 은 다른 날짜로 이미 반영된 수면.
 */
type Decision =
  | { outcome: 'applied'; earnedXp: number; stats: StatDelta }
  | { outcome: 'tooShort' }
  | { outcome: 'duplicate' }
  | { outcome: 'overlap' }
  | { outcome: 'sleepOverlap' }
  | { outcome: 'rejected'; reason: LocalizedMessage };

interface SyncState {
  request: SyncRequest;
  character: UserCharacter;
  requestKeys: Set<string>;
  acceptedWorkouts: ActivityPayload[];
  /** 날짜별 남은 총 상한·운동 상한. 처음 쓸 때 DB 합계로 채운다 */
  remainingDailyXp: Map<LocalDate, number>;
  remainingWorkoutXp: Map<LocalDate, number>;
  effectiveSteps: number;
  effectiveSleep: number;
}

/**
 * 건강 활동 동기화(Spring HealthActivitySyncService + #107 규칙).
 * - 검증 → 신규 사용자 캐릭터 생성(자동 커밋) → 한 트랜잭션에서 캐릭터 행 잠금(같은 사용자 동시 동기화 직렬화) 후 처리
 * - STEPS·SLEEP 은 날짜별 1건(같은 요청에서는 최대값 하나), WORKOUT 은 시작·종료·종류로 중복과 시간 겹침을 막는다
 * - 일일 XP 상한 600, 운동 상한 400(운동은 시작 시각의 현지 날짜 기준)
 * - #107: 5분 미만 운동은 기록만 하고 XP·능력치 없음(겹침 판정에서도 제외), 능력치는 XP 가 실제 지급될 때만 반영,
 *   같은 수면을 다른 날짜로 다시 보내면 건너뜀
 */
export class HealthActivitySyncService {
  constructor(
    private readonly validator: HealthActivitySyncValidator,
    private readonly zone: string,
    private readonly clock: () => number,
  ) {}

  async sync(db: Db, request: SyncRequest, locale: Locale): Promise<SyncResponse> {
    this.validator.validate(request.date, request.activities);
    await characters.createIfAbsent(db, request.userId, Instant.ofEpochMilli(this.clock()));
    return transaction(db, (tx) => this.syncInTransaction(tx, request, locale));
  }

  private async syncInTransaction(tx: Db, request: SyncRequest, locale: Locale): Promise<SyncResponse> {
    const activities = request.activities.map(withResolvedDuration);
    const character = await characters.findByUserIdForUpdate(tx, request.userId);
    if (!character) throw new Error(`캐릭터가 없습니다: ${request.userId}`);
    const state: SyncState = {
      request,
      character,
      requestKeys: new Set(),
      acceptedWorkouts: [],
      remainingDailyXp: new Map(),
      remainingWorkoutXp: new Map(),
      effectiveSteps: -1,
      effectiveSleep: -1,
    };
    // 이 요청이 로그를 쓰기 전에 관련 날짜의 남은 상한을 모두 읽어 둔다(이후 저장분이 합계에 이중 반영되지 않게)
    await this.remainingDaily(tx, state, request.date);
    for (const activity of activities) {
      if (activity.type === 'WORKOUT') {
        await this.remainingDaily(tx, state, this.xpDate(request, activity));
        await this.remainingWorkout(tx, state, this.xpDate(request, activity));
      }
    }

    const rejections = activities.map((activity) => this.validator.rejection(request.date, activity));
    // 같은 요청 내 STEPS/SLEEP 은 (값 범위를 통과한 것 중) 최대값 하나만 반영한다
    state.effectiveSteps = maxPayloadIndex(activities, rejections, 'STEPS', (a) => a.steps);
    state.effectiveSleep = maxPayloadIndex(activities, rejections, 'SLEEP', (a) => a.sleepMinutes);

    let gainedXp = 0;
    let levelUp = false;
    const results: ActivityResult[] = [];
    const now = Instant.ofEpochMilli(this.clock());

    for (let i = 0; i < activities.length; i++) {
      const activity = activities[i]!;
      const src = source(activity);
      const key = externalKey(request, activity);
      const decision = await this.decide(tx, state, activity, i, rejections[i] ?? null, key, src, now);
      if (decision.outcome === 'tooShort') {
        // 기록은 남기되(재전송은 중복, goals 반영) XP·스탯·상한 차감은 없다
        await healthActivities.insert(tx, toEntity(request, this.xpDate(request, activity), activity, 0, key, src, now));
        results.push({
          type: activity.type,
          source: src,
          gainedXp: 0,
          message: message(locale, 'activity.result.too-short', displayName(locale, activity), MIN_WORKOUT_MINUTES),
          duplicate: false,
        });
        continue;
      }
      if (decision.outcome !== 'applied') {
        results.push({ type: activity.type, source: src, gainedXp: 0, message: skipMessage(locale, activity, decision), duplicate: true });
        continue;
      }

      // 운동 XP 는 운동 시작 시각의 현지 날짜 기준으로 상한을 적용한다(요청 date 를 바꿔 나눠 보내도 우회 불가)
      const xpDate = this.xpDate(request, activity);
      let awardedXp = capToDaily(decision.earnedXp, await this.remainingDaily(tx, state, xpDate));
      if (activity.type === 'WORKOUT') {
        awardedXp = Math.min(awardedXp, await this.remainingWorkout(tx, state, xpDate));
        state.remainingWorkoutXp.set(xpDate, (state.remainingWorkoutXp.get(xpDate) ?? 0) - awardedXp);
      }
      state.remainingDailyXp.set(xpDate, (state.remainingDailyXp.get(xpDate) ?? 0) - awardedXp);
      if (activity.type === 'WORKOUT') {
        // STEPS/SLEEP 로그는 upsert 에서 같은 규칙(capToDaily)으로 실제 지급액을 기록한다
        await healthActivities.insert(tx, toEntity(request, xpDate, activity, awardedXp, key, src, now));
      }
      // #107: 능력치는 XP 가 실제로 지급된 경우에만(상한에 걸리거나 5분 미만 운동이면 능력치도 없음)
      if (awardedXp > 0) applyStats(character.stats, decision.stats);
      levelUp = character.addXp(awardedXp) || levelUp;
      gainedXp += awardedXp;
      results.push({
        type: activity.type,
        source: src,
        gainedXp: awardedXp,
        message: rewardMessage(locale, activity, awardedXp, awardedXp < decision.earnedXp),
        duplicate: false,
      });
    }

    await characters.update(tx, character, now);
    return {
      userId: request.userId,
      date: request.date,
      gainedXp,
      levelUp,
      character: characterResponse(character),
      activityResults: results,
      goals: await dailyGoals(tx, request.userId, request.date),
    };
  }

  private async decide(
    tx: Db,
    state: SyncState,
    activity: ActivityPayload,
    index: number,
    rejection: LocalizedMessage | null,
    key: string,
    src: string,
    now: Instant,
  ): Promise<Decision> {
    if (rejection) return { outcome: 'rejected', reason: rejection };
    switch (activity.type) {
      case 'STEPS':
        return index === state.effectiveSteps ? this.upsertSteps(tx, state, activity, key, src, now) : { outcome: 'duplicate' };
      case 'SLEEP':
        return index === state.effectiveSleep ? this.upsertSleep(tx, state, activity, key, src, now) : { outcome: 'duplicate' };
      case 'WORKOUT':
        return this.decideWorkout(tx, state, activity, key);
    }
  }

  private async decideWorkout(tx: Db, state: SyncState, activity: ActivityPayload, key: string): Promise<Decision> {
    if (state.requestKeys.has(key)) return { outcome: 'duplicate' };
    state.requestKeys.add(key);
    if (await healthActivities.existsByExternalKey(tx, key)) return { outcome: 'duplicate' };
    // 다른 운동 기록과 시간 구간이 겹치면 이 항목만 건너뛴다(먼저 반영된 기록 우선).
    // 5분 미만 운동(0 XP 기록)은 겹침 판정에서 빼서, 먼저 들어온 짧은 기록이 실제 운동을 막지 않게 한다
    const stored = await healthActivities.findOverlapping(tx, state.request.userId, 'WORKOUT', activity.startedAt!, activity.endedAt!);
    const overlapsStored = stored.some((log) => !isTooShort(log.started_at!, log.ended_at!, log.duration_minutes));
    if (overlapsStored || overlapsAccepted(state.acceptedWorkouts, activity)) return { outcome: 'overlap' };
    // acceptedWorkouts 에도 넣지 않는다(같은 요청의 실제 운동을 막지 않음)
    if (isTooShort(activity.startedAt!, activity.endedAt!, activity.durationMinutes)) return { outcome: 'tooShort' };
    state.acceptedWorkouts.push(activity);
    return { outcome: 'applied', earnedXp: calculateXp(activity), stats: workoutStats(activity) };
  }

  /**
   * 그날의 STEPS 로그를 생성하거나 더 큰 걸음 수로 갱신하고 새로 지급할 XP 차액(상한 적용 전)과 지급 시 줄 능력치를 돌려준다.
   * 걸음 수가 늘지 않았으면 duplicate. 로그 gained_xp 에는 상한 적용 후 실제 지급 누적액을 기록한다.
   * DISCIPLINE(XP 가 실제 지급될 때만): 기본 +1 은 그날 로그에 아직 지급된 XP 가 없을 때 1회,
   * 목표 +1 은 목표 미달 → 달성 전환 시 1회(기본 미지급 상태면 달성 여부만 본다)
   */
  private async upsertSteps(tx: Db, state: SyncState, activity: ActivityPayload, key: string, src: string, now: Instant): Promise<Decision> {
    const newSteps = Math.max(activity.steps ?? 0, 0);
    const newXp = stepsXp(newSteps);
    const date = state.request.date;
    const existing = await healthActivities.findByExternalKey(tx, key);
    if (!existing) {
      const awarded = capToDaily(newXp, await this.remainingDaily(tx, state, date));
      await healthActivities.insert(tx, toEntity(state.request, date, activity, awarded, key, src, now));
      return { outcome: 'applied', earnedXp: newXp, stats: stepsStats(true, crossesStepsGoal(0, newSteps)) };
    }
    const previousSteps = existing.steps ?? 0;
    if (newSteps <= previousSteps) return { outcome: 'duplicate' };
    const basePending = existing.gained_xp === 0;
    const goalReached = crossesStepsGoal(basePending ? 0 : previousSteps, newSteps);
    // 같은 1,000보 구간이거나 상한에 도달했으면 차액은 0 이다. 이미 지급한 XP 는 회수하지 않는다
    const xpDelta = Math.max(newXp - existing.gained_xp, 0);
    await healthActivities.updateSteps(
      tx,
      existing.id,
      newSteps,
      existing.gained_xp + capToDaily(xpDelta, await this.remainingDaily(tx, state, date)),
    );
    return { outcome: 'applied', earnedXp: xpDelta, stats: stepsStats(basePending, goalReached) };
  }

  /**
   * 그날의 SLEEP 로그(날짜별 1건)를 생성하거나 더 긴 수면으로 갱신하고 XP 차액과 지급 시 줄 능력치를 돌려준다.
   * 다른 날짜로 이미 반영된 수면과 겹치고 그보다 늦게 깨지 않았으면 sleepOverlap.
   * 능력치(XP 가 실제 지급될 때만): 그날 아직 지급된 XP 가 없으면 RECOVERY +1·INT +1(품질 기준 이상이면 INT +1 추가),
   * 이미 지급됐으면 수면 점수가 품질 기준 미만 → 이상으로 처음 넘어갈 때 INT +1
   */
  private async upsertSleep(tx: Db, state: SyncState, activity: ActivityPayload, key: string, src: string, now: Instant): Promise<Decision> {
    if (await this.claimedUnderOtherDate(tx, state, activity)) return { outcome: 'sleepOverlap' };
    const newMinutes = activity.sleepMinutes ?? 0;
    const newScore = resolvedSleepScore(newMinutes, activity.sleepScore);
    const newXp = sleepXp(newMinutes, activity.sleepScore);
    const date = state.request.date;
    const existing = await healthActivities.findByExternalKey(tx, key);
    if (!existing) {
      const awarded = capToDaily(newXp, await this.remainingDaily(tx, state, date));
      await healthActivities.insert(tx, toEntity(state.request, date, activity, awarded, key, src, now));
      return { outcome: 'applied', earnedXp: newXp, stats: sleepStats(true, meetsSleepQuality(newScore)) };
    }
    const previousMinutes = existing.sleep_minutes ?? 0;
    if (newMinutes <= previousMinutes) return { outcome: 'duplicate' };
    const basePending = existing.gained_xp === 0;
    const previousScore = resolvedSleepScore(previousMinutes, existing.sleep_score);
    const qualityReached = meetsSleepQuality(newScore) && (basePending || !meetsSleepQuality(previousScore));
    const xpDelta = Math.max(newXp - existing.gained_xp, 0);
    await healthActivities.updateSleep(tx, existing.id, {
      sleepMinutes: newMinutes,
      sleepScore: activity.sleepScore,
      startedAt: activity.startedAt,
      endedAt: activity.endedAt,
      gainedXp: existing.gained_xp + capToDaily(xpDelta, await this.remainingDaily(tx, state, date)),
    });
    return { outcome: 'applied', earnedXp: xpDelta, stats: sleepStats(basePending, qualityReached) };
  }

  /**
   * 같은 수면을 다른 날짜로 다시 받으려는지. 다른 날짜(activity_date)의 SLEEP 로그와 구간이 겹치고 새 수면이 그 로그보다
   * 늦게 끝나지 않으면 이미 반영된 수면으로 본다. 같은 날짜 로그는 대상이 아니다
   */
  private async claimedUnderOtherDate(tx: Db, state: SyncState, activity: ActivityPayload): Promise<boolean> {
    const overlapping = await healthActivities.findOverlapping(tx, state.request.userId, 'SLEEP', activity.startedAt!, activity.endedAt!);
    return overlapping.some((log) => log.activity_date !== state.request.date && !log.ended_at!.isBefore(activity.endedAt!));
  }

  /** XP 상한을 적용할 날짜. WORKOUT 은 시작 시각의 서버 현지 날짜(로그 activity_date 도 같음), 나머지는 요청 date */
  private xpDate(request: SyncRequest, activity: ActivityPayload): LocalDate {
    return activity.type === 'WORKOUT' ? localDateOf(activity.startedAt!, this.zone) : request.date;
  }

  private async remainingDaily(tx: Db, state: SyncState, date: LocalDate): Promise<number> {
    let value = state.remainingDailyXp.get(date);
    if (value === undefined) {
      value = Math.max(0, DAILY_XP_CAP - (await healthActivities.sumGainedXp(tx, state.request.userId, date)));
      state.remainingDailyXp.set(date, value);
    }
    return value;
  }

  private async remainingWorkout(tx: Db, state: SyncState, date: LocalDate): Promise<number> {
    let value = state.remainingWorkoutXp.get(date);
    if (value === undefined) {
      value = Math.max(0, DAILY_WORKOUT_XP_CAP - (await healthActivities.sumGainedXp(tx, state.request.userId, date, 'WORKOUT')));
      state.remainingWorkoutXp.set(date, value);
    }
    return value;
  }
}

/** 그날 기록 기준 목표 달성 현황(걸음 최대값, 운동 분 합계, 수면 최대값) */
async function dailyGoals(tx: Db, userId: string, date: LocalDate): Promise<Goal[]> {
  let steps = 0;
  let workoutMinutes = 0;
  let sleepMinutes = 0;
  for (const activity of await healthActivities.findByUserIdAndDate(tx, userId, date)) {
    if (activity.type === 'STEPS') steps = Math.max(steps, activity.steps ?? 0);
    else if (activity.type === 'WORKOUT') workoutMinutes = saturatedAdd(workoutMinutes, activity.duration_minutes ?? 0);
    else sleepMinutes = Math.max(sleepMinutes, activity.sleep_minutes ?? 0);
  }
  const goal = (type: ActivityType, target: number, current: number, unit: string): Goal => ({
    type,
    target,
    current,
    unit,
    achieved: current >= target,
  });
  return [
    goal('STEPS', STEPS_GOAL, steps, 'steps'),
    goal('WORKOUT', WORKOUT_GOAL_MINUTES, workoutMinutes, 'minutes'),
    goal('SLEEP', SLEEP_GOAL_MINUTES, sleepMinutes, 'minutes'),
  ];
}

/** WORKOUT 의 durationMinutes 가 비어 있으면 시작~종료 구간 길이(분, 검증과 같은 반올림)로 채운다 */
function withResolvedDuration(activity: ActivityPayload): ActivityPayload {
  if (activity.type !== 'WORKOUT' || activity.durationMinutes !== null) return activity;
  return { ...activity, durationMinutes: Math.min(spanMinutes(activity.startedAt!, activity.endedAt!), 2_147_483_647) };
}

/** 실제 구간이나 활동 시간(durationMinutes)이 최소 운동 시간(5분) 미만인 운동. 저장된 로그도 같은 기준으로 판정한다 */
function isTooShort(startedAt: Instant, endedAt: Instant, durationMinutes: number | null): boolean {
  return startedAt.plusSeconds(MIN_WORKOUT_MINUTES * 60).isAfter(endedAt) || (durationMinutes ?? 0) < MIN_WORKOUT_MINUTES;
}

function stepsStats(base: boolean, goalReached: boolean): StatDelta {
  return { discipline: (base ? 1 : 0) + (goalReached ? 1 : 0) };
}

function sleepStats(base: boolean, qualityReached: boolean): StatDelta {
  return { recovery: base ? 1 : 0, intStat: (base ? 1 : 0) + (qualityReached ? 1 : 0) };
}

function maxPayloadIndex(
  activities: ActivityPayload[],
  rejections: (LocalizedMessage | null)[],
  type: ActivityType,
  valueOf: (a: ActivityPayload) => number | null,
): number {
  let max = -1;
  activities.forEach((activity, i) => {
    if (activity.type === type && rejections[i] === null && (max < 0 || (valueOf(activity) ?? 0) > (valueOf(activities[max]!) ?? 0))) {
      max = i;
    }
  });
  return max;
}

function overlapsAccepted(accepted: ActivityPayload[], activity: ActivityPayload): boolean {
  return accepted.some((other) => other.startedAt!.isBefore(activity.endedAt!) && activity.startedAt!.isBefore(other.endedAt!));
}

function workoutStats(activity: ActivityPayload): StatDelta {
  switch (activity.workoutType ?? 'OTHER') {
    case 'STRENGTH_TRAINING':
      return { str: 2, vit: 1 };
    case 'SWIMMING':
    case 'RUNNING':
    case 'CYCLING':
    case 'WALKING':
      return { vit: 2, str: 1 };
    default:
      return { str: 1, vit: 1 };
  }
}

function applyStats(stats: CharacterStats, delta: StatDelta) {
  for (const key of Object.keys(delta) as (keyof CharacterStats)[]) {
    stats[key] += delta[key] ?? 0;
  }
}

function crossesStepsGoal(previousSteps: number, newSteps: number): boolean {
  return previousSteps < STEPS_GOAL && newSteps >= STEPS_GOAL;
}

function meetsSleepQuality(score: number): boolean {
  return score >= SLEEP_QUALITY_BONUS_SCORE;
}

function capToDaily(earnedXp: number, remainingDailyXp: number): number {
  return Math.max(0, Math.min(earnedXp, remainingDailyXp));
}

export function externalKey(request: SyncRequest, activity: ActivityPayload): string {
  switch (activity.type) {
    case 'STEPS':
      return [request.userId, request.date, 'STEPS'].join('|');
    case 'WORKOUT':
      return [request.userId, activity.startedAt!.toString(), activity.endedAt!.toString(), source(activity)].join('|');
    case 'SLEEP':
      return [request.userId, request.date, 'SLEEP'].join('|');
  }
}

export function source(activity: ActivityPayload): string {
  return activity.type === 'WORKOUT' ? (activity.workoutType ?? 'OTHER') : activity.type;
}

/** 사용자 문구에 넣을 활동 이름(요청 로케일). 응답 source 필드는 코드 값 그대로 둔다 */
function displayName(locale: Locale, activity: ActivityPayload): string {
  const prefix = activity.type === 'WORKOUT' ? 'workout.name.' : 'activity.name.';
  return message(locale, prefix + source(activity));
}

function skipMessage(locale: Locale, activity: ActivityPayload, decision: Exclude<Decision, { outcome: 'applied' | 'tooShort' }>): string {
  const name = displayName(locale, activity);
  switch (decision.outcome) {
    case 'overlap':
      return message(locale, 'activity.result.overlap', name);
    case 'sleepOverlap':
      return message(locale, 'activity.result.sleep-other-date', name);
    case 'rejected':
      return message(locale, 'activity.result.rejected', name, resolve(locale, decision.reason));
    default:
      return message(locale, 'activity.result.duplicate', name);
  }
}

function rewardMessage(locale: Locale, activity: ActivityPayload, xp: number, capped: boolean): string {
  const text =
    activity.type === 'STEPS'
      ? message(locale, 'activity.reward.steps', xp)
      : activity.type === 'WORKOUT'
        ? message(locale, 'activity.reward.workout', displayName(locale, activity), xp)
        : message(locale, 'activity.reward.sleep', xp);
  return capped ? message(locale, 'activity.reward.capped', text) : text;
}

function toEntity(
  request: SyncRequest,
  activityDate: LocalDate,
  activity: ActivityPayload,
  xp: number,
  key: string,
  src: string,
  now: Instant,
) {
  return {
    id: randomUuid(),
    userId: request.userId,
    activityDate,
    type: activity.type,
    sourceType: src,
    durationMinutes: activity.durationMinutes,
    calories: activity.calories ?? 0,
    distanceMeters: activity.distanceMeters ?? 0,
    steps: activity.steps,
    sleepMinutes: activity.sleepMinutes,
    sleepScore: activity.sleepScore,
    gainedXp: xp,
    externalKey: key,
    startedAt: activity.startedAt,
    endedAt: activity.endedAt,
    createdAt: now,
  };
}
