import { addDays, localDateOf, startOfDay, today, type LocalDate } from '../lib/dates.js';
import { badRequest, type LocalizedMessage } from '../lib/errors.js';
import { Instant } from '../lib/instant.js';
import type { ActivityPayload } from './types.js';

/** Spring HealthActivitySyncValidator 상수 */
export const MAX_ACTIVITIES_PER_REQUEST = 50;
export const SYNC_DATE_TOLERANCE_DAYS = 1;
export const SLEEP_WAKE_DATE_TOLERANCE_DAYS = 1;
export const FUTURE_TOLERANCE_SECONDS = 5 * 60;

export const MAX_STEPS = 100_000;
export const MAX_SLEEP_MINUTES = 16 * 60;
export const MAX_WORKOUT_MINUTES = 12 * 60;
export const MAX_CALORIES = 10_000;
export const MAX_CALORIES_PER_MINUTE = 20;
export const MAX_DISTANCE_METERS = 500_000;
export const MIN_SLEEP_SCORE = 0;
export const MAX_SLEEP_SCORE = 100;

/**
 * 동기화 요청 검증(Spring HealthActivitySyncValidator + #107 규칙).
 * - validate: 구조 오류(필수 필드 누락, 종료 < 시작, 개수 초과, 날짜가 오늘 ±1일 밖) → 요청 전체 400
 * - rejection: 값 범위 위반 → 사유를 돌려주고 그 항목만 건너뛴다
 * 날짜 계산은 APP_TIMEZONE(기본 Asia/Seoul) 기준이다.
 */
export class HealthActivitySyncValidator {
  constructor(
    private readonly zone: string,
    private readonly clock: () => number,
  ) {}

  // ---- 구조 검증 (요청 전체 400) ----

  validate(date: LocalDate | null, activities: ActivityPayload[] | null): void {
    require(!!activities && activities.length > 0, 'sync.error.activities-empty');
    require(activities!.length <= MAX_ACTIVITIES_PER_REQUEST, 'sync.error.too-many-activities', MAX_ACTIVITIES_PER_REQUEST);
    require(date !== null, 'sync.error.date-required');
    const now = today(this.clock(), this.zone);
    require(
      date! >= addDays(now, -SYNC_DATE_TOLERANCE_DAYS) && date! <= addDays(now, SYNC_DATE_TOLERANCE_DAYS),
      'sync.error.date-out-of-range',
      SYNC_DATE_TOLERANCE_DAYS,
    );
    activities!.forEach((activity) => validateStructure(activity));
  }

  // ---- 값 범위 검증 (해당 항목만 건너뛰기) ----

  rejection(date: LocalDate, activity: ActivityPayload): LocalizedMessage | null {
    const timeReason = this.timeReason(date, activity);
    if (timeReason) return timeReason;
    switch (activity.type) {
      case 'STEPS':
        return this.stepsReason(date, activity);
      case 'WORKOUT':
        return workoutReason(activity);
      case 'SLEEP':
        return this.sleepReason(date, activity);
    }
  }

  /** 미래 시각(5분 허용)과 날짜 창: startedAt 은 date 전날 00:00 이후, endedAt 은 date 다음 날 24:00 이전 */
  private timeReason(date: LocalDate, activity: ActivityPayload): LocalizedMessage | null {
    const latestAllowed = Instant.ofEpochMilli(this.clock()).plusSeconds(FUTURE_TOLERANCE_SECONDS);
    if (isAfter(activity.startedAt, latestAllowed) || isAfter(activity.endedAt, latestAllowed)) {
      return { code: 'sync.reason.future-time' };
    }
    const windowStart = startOfDay(addDays(date, -1), this.zone);
    const windowEnd = startOfDay(addDays(date, 2), this.zone);
    if ((activity.startedAt && activity.startedAt.isBefore(windowStart)) || isAfter(activity.endedAt, windowEnd)) {
      return { code: 'sync.reason.outside-date-window' };
    }
    return null;
  }

  /** 걸음 수 범위와 날짜: 서버 기준 오늘보다 뒤 날짜(내일)의 걸음 수는 받지 않는다(#107) */
  private stepsReason(date: LocalDate, activity: ActivityPayload): LocalizedMessage | null {
    if (date > today(this.clock(), this.zone)) return { code: 'sync.reason.steps-future-date' };
    return withinRange(activity.steps!, 0, MAX_STEPS) ? null : { code: 'sync.reason.steps-range', args: [MAX_STEPS] };
  }

  /**
   * 수면의 깬 시각(endedAt)은 서버 현지 날짜 기준 요청 date ±1일 안이어야 한다(#107). 요청 date 는 기기 현지 날짜라
   * 서버 시간대와 하루 어긋날 수 있다. 같은 수면을 날짜만 바꿔 다시 받는 것은 서비스가 다른 날짜 수면 로그와의 겹침으로 막는다.
   */
  private sleepReason(date: LocalDate, activity: ActivityPayload): LocalizedMessage | null {
    const wakeDate = localDateOf(activity.endedAt!, this.zone);
    if (wakeDate < addDays(date, -SLEEP_WAKE_DATE_TOLERANCE_DAYS) || wakeDate > addDays(date, SLEEP_WAKE_DATE_TOLERANCE_DAYS)) {
      return { code: 'sync.reason.sleep-date-mismatch', args: [wakeDate] };
    }
    const minutesReason = rangeAndSpan(
      activity.sleepMinutes!,
      MAX_SLEEP_MINUTES,
      spanMinutes(activity.startedAt!, activity.endedAt!),
      'sync.reason.sleep-minutes-range',
      'sync.reason.sleep-minutes-exceed-span',
    );
    if (minutesReason) return minutesReason;
    if (activity.sleepScore !== null && !withinRange(activity.sleepScore, MIN_SLEEP_SCORE, MAX_SLEEP_SCORE)) {
      return { code: 'sync.reason.sleep-score-range', args: [MIN_SLEEP_SCORE, MAX_SLEEP_SCORE] };
    }
    return null;
  }
}

function validateStructure(activity: ActivityPayload) {
  switch (activity.type) {
    case 'STEPS':
      require(activity.steps !== null, 'sync.error.steps-required');
      break;
    case 'WORKOUT':
      require(activity.startedAt !== null && activity.endedAt !== null, 'sync.error.workout-times-required');
      require(!activity.endedAt!.isBefore(activity.startedAt!), 'sync.error.end-before-start');
      break;
    case 'SLEEP':
      require(
        activity.sleepMinutes !== null && activity.startedAt !== null && activity.endedAt !== null,
        'sync.error.sleep-fields-required',
      );
      require(!activity.endedAt!.isBefore(activity.startedAt!), 'sync.error.end-before-start');
      break;
  }
}

function workoutReason(activity: ActivityPayload): LocalizedMessage | null {
  const startedAt = activity.startedAt!;
  const endedAt = activity.endedAt!;
  if (!startedAt.isBefore(endedAt)) return { code: 'sync.reason.workout-end-not-after-start' };
  const spanSeconds = startedAt.secondsUntil(endedAt);
  if (startedAt.minutesUntil(endedAt) > MAX_WORKOUT_MINUTES) {
    return { code: 'sync.reason.workout-too-long', args: [MAX_WORKOUT_MINUTES / 60] };
  }
  const span = spanMinutes(startedAt, endedAt);
  const minutesReason = rangeAndSpan(
    activity.durationMinutes ?? span,
    MAX_WORKOUT_MINUTES,
    span,
    'sync.reason.workout-minutes-range',
    'sync.reason.workout-minutes-exceed-span',
  );
  if (minutesReason) return minutesReason;
  if (!withinDecimal(activity.calories, MAX_CALORIES)) return { code: 'sync.reason.calories-range', args: [MAX_CALORIES] };
  // calories > 분당 상한 × 구간(초 단위까지) ⇔ calories × 60 > 20 × seconds. 칼로리는 소수 둘째 자리까지라
  // 1/100 kcal 정수로 바꿔 비교한다(부동소수 경계 오차 방지)
  if (activity.calories !== null && Math.round(activity.calories * 100) * 60 > MAX_CALORIES_PER_MINUTE * 100 * spanSeconds) {
    return { code: 'sync.reason.calories-per-minute', args: [MAX_CALORIES_PER_MINUTE] };
  }
  if (!withinDecimal(activity.distanceMeters, MAX_DISTANCE_METERS)) {
    return { code: 'sync.reason.distance-range', args: [MAX_DISTANCE_METERS] };
  }
  return null;
}

/**
 * 시작~종료 구간 길이(분). 초 단위 구간을 분으로 반올림(30초 이상 올림)한다(Spring spanMinutes, #107).
 * 클라이언트도 활동·수면 시간을 같은 방식으로 반올림해 보내므로 별도 허용 오차 없이 "분 ≤ spanMinutes" 로 검증한다.
 */
export function spanMinutes(startedAt: Instant, endedAt: Instant): number {
  return Math.trunc((startedAt.secondsUntil(endedAt) + 30) / 60);
}

/** 분 값의 범위(0~max)와 구간 길이(반올림한 분, 허용 오차 없음) 검증 */
function rangeAndSpan(minutes: number, max: number, span: number, rangeKey: string, spanKey: string): LocalizedMessage | null {
  if (!withinRange(minutes, 0, max)) return { code: rangeKey, args: [max] };
  if (minutes > span) return { code: spanKey };
  return null;
}

function isAfter(time: Instant | null, limit: Instant): boolean {
  return time !== null && time.isAfter(limit);
}

function withinRange(value: number, min: number, max: number): boolean {
  return value >= min && value <= max;
}

function withinDecimal(value: number | null, max: number): boolean {
  return value === null || (value >= 0 && value <= max);
}

function require(condition: boolean, code: string, ...args: number[]): void {
  if (!condition) throw badRequest(code, ...args);
}
