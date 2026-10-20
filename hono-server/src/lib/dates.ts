import { Instant, daysInMonth } from './instant.js';

/** java.time.LocalDate 를 'yyyy-MM-dd' 문자열로 다룬다(같은 길이라 문자열 비교 = 날짜 비교). */
export type LocalDate = string;

/** Jackson LocalDateDeserializer 처럼 엄격한 'yyyy-MM-dd'. 틀리면 null. */
export function parseLocalDate(text: string): LocalDate | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]);
  const d = Number(m[3]);
  if (mo < 1 || mo > 12 || d < 1 || d > daysInMonth(y, mo)) return null;
  return text;
}

export function addDays(date: LocalDate, days: number): LocalDate {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return formatUtcDate(new Date(Date.UTC(y, m - 1, d + days)));
}

function formatUtcDate(date: Date): LocalDate {
  return `${String(date.getUTCFullYear()).padStart(4, '0')}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(
    date.getUTCDate(),
  ).padStart(2, '0')}`;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatters.set(zone, f);
  }
  return f;
}

/** 시간대가 올바른지(기동 시 설정 검증용) */
export function isValidZone(zone: string): boolean {
  try {
    formatter(zone);
    return true;
  } catch {
    return false;
  }
}

/** epoch ms 시점의 zone 오프셋(ms) */
function offsetMillis(epochMs: number, zone: string): number {
  const parts = Object.fromEntries(formatter(zone).formatToParts(new Date(epochMs)).map((p) => [p.type, p.value]));
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  );
  return asUtc - Math.floor(epochMs / 1000) * 1000;
}

/** LocalDate.ofInstant(instant, zone) */
export function localDateOf(instant: Instant, zone: string): LocalDate {
  const ms = instant.epochSecond * 1000;
  return formatUtcDate(new Date(ms + offsetMillis(ms, zone)));
}

/** LocalDate.now(clock) */
export function today(nowMs: number, zone: string): LocalDate {
  return localDateOf(Instant.ofEpochMilli(nowMs), zone);
}

/** date.atStartOfDay(zone).toInstant() — DST 틈이 있으면 그 날의 첫 유효 시각 */
export function startOfDay(date: LocalDate, zone: string): Instant {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  const localMidnightAsUtc = Date.UTC(y, m - 1, d);
  let guess = localMidnightAsUtc - offsetMillis(localMidnightAsUtc, zone);
  guess = localMidnightAsUtc - offsetMillis(guess, zone);
  // 자정이 DST 틈에 빠지면 위 계산이 전날로 갈 수 있다. 그 날짜에 속하는 첫 시각으로 맞춘다.
  if (localDateOf(Instant.ofEpochMilli(guess), zone) < date) {
    guess += 3_600_000;
  }
  return Instant.ofEpochMilli(guess);
}
