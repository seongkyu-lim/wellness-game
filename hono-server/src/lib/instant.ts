/**
 * java.time.Instant 와 같은 의미의 시각(초 + 나노초).
 *
 * JS Date 는 밀리초까지만 담아서, 클라이언트가 보낸 시각의 문자열 표현(운동 externalKey 에 들어감)과
 * 응답의 시각 문자열을 Spring(Jackson) 과 똑같이 만들려면 나노초를 보존해야 한다.
 * - 파싱: Jackson InstantDeserializer(ISO_INSTANT, 오프셋 허용) 또는 epoch 초 숫자
 * - 출력: Instant.toString() — 초는 항상, 소수부는 0 이면 생략하고 3·6·9 자리 단위로 출력
 *   (iOS 의 JSONDecoder .iso8601 은 소수부가 있으면 실패하므로 ".000" 을 붙이면 안 된다)
 */
export class Instant {
  private constructor(
    /** epoch 초(음수 가능) */
    readonly epochSecond: number,
    /** 0..999_999_999 */
    readonly nano: number,
  ) {}

  static ofEpochMilli(ms: number): Instant {
    const s = Math.floor(ms / 1000);
    return new Instant(s, Math.round((ms - s * 1000) * 1_000_000));
  }

  static ofEpochSecond(s: number, nano = 0): Instant {
    const carry = Math.floor(nano / 1_000_000_000);
    return new Instant(s + carry, nano - carry * 1_000_000_000);
  }

  static now(clock: () => number = Date.now): Instant {
    return Instant.ofEpochMilli(clock());
  }

  /**
   * ISO-8601 시각 문자열. 'Z' 또는 ±hh:mm 오프셋이 필요하다(오프셋 없는 로컬 시각은 Jackson 도 거부).
   * 형식이 틀리면 null.
   */
  static parse(text: string): Instant | null {
    const m =
      /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:([Zz])|([+-])(\d{2}):?(\d{2})(?::?(\d{2}))?)$/.exec(
        text,
      );
    if (!m) return null;
    const [year, month, day, hour, minute] = [m[1], m[2], m[3], m[4], m[5]].map(Number) as [
      number,
      number,
      number,
      number,
      number,
    ];
    const second = m[6] ? Number(m[6]) : 0;
    if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null;
    if (hour > 23 || minute > 59 || second > 59) return null;
    const nano = m[7] ? Number(m[7].padEnd(9, '0')) : 0;
    let offsetSeconds = 0;
    if (!m[8]) {
      const sign = m[9] === '-' ? -1 : 1;
      const oh = Number(m[10]);
      const om = Number(m[11]);
      const os = m[12] ? Number(m[12]) : 0;
      if (oh > 18 || om > 59 || os > 59) return null;
      offsetSeconds = sign * (oh * 3600 + om * 60 + os);
    }
    const epochDay = Math.floor(Date.UTC(year, month - 1, day) / 86_400_000);
    const epochSecond = epochDay * 86_400 + hour * 3600 + minute * 60 + second - offsetSeconds;
    return new Instant(epochSecond, nano);
  }

  /** Jackson 은 숫자를 epoch 초(소수부 = 나노초)로 읽는다. */
  static fromEpochSecondsNumber(value: number): Instant | null {
    if (!Number.isFinite(value)) return null;
    const s = Math.floor(value);
    return Instant.ofEpochSecond(s, Math.round((value - s) * 1_000_000_000));
  }

  toEpochMilli(): number {
    return this.epochSecond * 1000 + Math.floor(this.nano / 1_000_000);
  }

  compareTo(other: Instant): number {
    if (this.epochSecond !== other.epochSecond) return this.epochSecond < other.epochSecond ? -1 : 1;
    if (this.nano !== other.nano) return this.nano < other.nano ? -1 : 1;
    return 0;
  }

  isBefore(other: Instant): boolean {
    return this.compareTo(other) < 0;
  }

  isAfter(other: Instant): boolean {
    return this.compareTo(other) > 0;
  }

  plusSeconds(seconds: number): Instant {
    return new Instant(this.epochSecond + seconds, this.nano);
  }

  /** Duration.between(this, end).getSeconds() — 음수 쪽으로 내림(Java Duration 과 같음) */
  secondsUntil(end: Instant): number {
    let s = end.epochSecond - this.epochSecond;
    if (end.nano < this.nano) s -= 1;
    return s;
  }

  /** Duration.between(this, end).toMinutes() — 0 쪽으로 버림 */
  minutesUntil(end: Instant): number {
    return Math.trunc(this.secondsUntil(end) / 60);
  }

  /** java.time.Instant#toString 과 같은 형식 */
  toString(): string {
    const epochDay = Math.floor(this.epochSecond / 86_400);
    const secOfDay = this.epochSecond - epochDay * 86_400;
    const date = new Date(epochDay * 86_400_000);
    const y = date.getUTCFullYear();
    const yearText = y > 9999 ? `+${y}` : y < 0 ? `-${String(-y).padStart(4, '0')}` : String(y).padStart(4, '0');
    const hh = Math.floor(secOfDay / 3600);
    const mm = Math.floor((secOfDay % 3600) / 60);
    const ss = secOfDay % 60;
    let text = `${yearText}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}T${pad2(hh)}:${pad2(mm)}:${pad2(ss)}`;
    if (this.nano > 0) {
      if (this.nano % 1_000_000 === 0) text += '.' + String(this.nano / 1_000_000).padStart(3, '0');
      else if (this.nano % 1000 === 0) text += '.' + String(this.nano / 1000).padStart(6, '0');
      else text += '.' + String(this.nano).padStart(9, '0');
    }
    return text + 'Z';
  }

  /** PostgreSQL 에 넘길 값(마이크로초 이하는 DB 가 반올림한다) */
  toSqlString(): string {
    return this.toString();
  }

  toJSON(): string {
    return this.toString();
  }
}

/**
 * PostgreSQL timestamptz 텍스트(세션 TimeZone=UTC)를 Instant 로. 예: "2026-10-20 01:02:03.123456+00"
 */
export function parsePgTimestamptz(text: string): Instant {
  const normalized = text.replace(' ', 'T').replace(/([+-]\d{2})$/, '$1:00');
  const parsed = Instant.parse(normalized);
  if (!parsed) throw new Error(`timestamptz 형식을 해석할 수 없습니다: ${text}`);
  return parsed;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}
