import { describe, expect, it } from 'vitest';
import { addDays, isValidZone, localDateOf, parseLocalDate, startOfDay, today } from '../../src/lib/dates.js';
import { parseDurationMs } from '../../src/lib/duration.js';
import { formatMessage, message, resolveLocale } from '../../src/lib/i18n.js';
import { daysInMonth, Instant, parsePgTimestamptz } from '../../src/lib/instant.js';
import { EN, KO } from '../../src/lib/messages-data.js';
import { sha256Hex, timingSafeEqual, randomBase64Url } from '../../src/lib/crypto.js';

describe('Instant 경계', () => {
  it('epoch 이전 시각의 밀리초·나노 변환(음수는 내림)', () => {
    const i = Instant.ofEpochMilli(-1);
    expect([i.epochSecond, i.nano]).toEqual([-1, 999_000_000]);
    expect(i.toString()).toBe('1969-12-31T23:59:59.999Z');
    expect(i.toEpochMilli()).toBe(-1);
    expect(Instant.ofEpochMilli(0).toString()).toBe('1970-01-01T00:00:00Z');
  });

  it('ofEpochSecond 는 나노초 올림·내림을 초에 반영한다', () => {
    expect(Instant.ofEpochSecond(10, 1_500_000_000)).toMatchObject({ epochSecond: 11, nano: 500_000_000 });
    expect(Instant.ofEpochSecond(10, -1)).toMatchObject({ epochSecond: 9, nano: 999_999_999 });
  });

  it('파싱: 소문자 t/z, 오프셋(+0900·+09:00:30), 초 생략, 9자리 나노', () => {
    expect(Instant.parse('2026-10-20t10:02:03z')!.toString()).toBe('2026-10-20T10:02:03Z');
    expect(Instant.parse('2026-10-20T10:02:03+0900')!.toString()).toBe('2026-10-20T01:02:03Z');
    expect(Instant.parse('2026-10-20T10:02:03-05:30')!.toString()).toBe('2026-10-20T15:32:03Z');
    expect(Instant.parse('2026-10-20T10:02:03+09:00:30')!.toString()).toBe('2026-10-20T01:01:33Z');
    expect(Instant.parse('2026-10-20T10:02Z')!.toString()).toBe('2026-10-20T10:02:00Z');
  });

  it.each([
    '2026-13-01T00:00:00Z',
    '2026-00-10T00:00:00Z',
    '2026-02-29T00:00:00Z', // 평년
    '2026-10-20T24:00:00Z',
    '2026-10-20T23:60:00Z',
    '2026-10-20T23:59:60Z',
    '2026-10-20T00:00:00+19:00',
    '2026-10-20T00:00:00+09:60',
    '2026-10-20 00:00:00Z',
    '2026-10-20T00:00:00.1234567890Z',
    '',
  ])('잘못된 시각 %j 는 null', (text) => {
    expect(Instant.parse(text)).toBeNull();
  });

  it('윤년 2월 29일은 허용', () => {
    expect(Instant.parse('2028-02-29T00:00:00Z')!.toString()).toBe('2028-02-29T00:00:00Z');
    expect(daysInMonth(2028, 2)).toBe(29);
    expect(daysInMonth(2100, 2)).toBe(28);
  });

  it('epoch 초 숫자: 소수부는 나노초, 비유한 값은 null', () => {
    expect(Instant.fromEpochSecondsNumber(1.5)!.toString()).toBe('1970-01-01T00:00:01.500Z');
    expect(Instant.fromEpochSecondsNumber(-0.5)!.toString()).toBe('1969-12-31T23:59:59.500Z');
    expect(Instant.fromEpochSecondsNumber(NaN)).toBeNull();
    expect(Instant.fromEpochSecondsNumber(Infinity)).toBeNull();
  });

  it('비교·구간: 나노 차이를 반영하고, 초 차이는 0 쪽이 아니라 내림, 분은 초(내림 값)를 60 으로 나눈 몫', () => {
    const a = Instant.parse('2026-10-20T00:00:00.900Z')!;
    const b = Instant.parse('2026-10-20T00:01:00.100Z')!;
    expect(a.isBefore(b)).toBe(true);
    expect(b.isAfter(a)).toBe(true);
    expect(a.compareTo(a)).toBe(0);
    expect(a.secondsUntil(b)).toBe(59); // 59.2초
    expect(b.secondsUntil(a)).toBe(-60); // -59.2초 → 내림 -60 (java Duration.getSeconds)
    expect(a.minutesUntil(b)).toBe(0);
    expect(b.minutesUntil(a)).toBe(-1); // Java Duration.toMinutes() = seconds(-60) / 60
    expect(Instant.parse('2026-10-20T00:00:00Z')!.minutesUntil(Instant.parse('2026-10-20T00:04:59Z')!)).toBe(4);
    expect(Instant.parse('2026-10-20T00:00:00.500Z')!.compareTo(Instant.parse('2026-10-20T00:00:00.400Z')!)).toBe(1);
  });

  it('toString 의 연도 표기(1000년 미만 4자리 채움, 9999 초과는 + 부호)', () => {
    expect(Instant.ofEpochSecond(-62_135_596_800 + 86_400 * 365).toString()).toMatch(/^0002-/);
    expect(Instant.ofEpochSecond(253_402_300_800).toString()).toBe('+10000-01-01T00:00:00Z');
    expect(JSON.stringify({ at: Instant.ofEpochMilli(1_000) })).toBe('{"at":"1970-01-01T00:00:01Z"}');
  });

  it('PostgreSQL timestamptz 텍스트(UTC 세션)를 마이크로초까지 보존해 읽는다', () => {
    expect(parsePgTimestamptz('2026-10-20 01:02:03.123456+00').toString()).toBe('2026-10-20T01:02:03.123456Z');
    expect(parsePgTimestamptz('2026-10-20 01:02:03+00').toString()).toBe('2026-10-20T01:02:03Z');
    expect(() => parsePgTimestamptz('infinity')).toThrow(/timestamptz/);
  });
});

describe('날짜·시간대 경계', () => {
  it('parseLocalDate 는 엄격하다(공백·1자리 월·불가능한 날짜 거부)', () => {
    expect(parseLocalDate('2028-02-29')).toBe('2028-02-29');
    for (const bad of ['2026-2-1', '2026-02-30', '2026-13-01', ' 2026-02-01', '2026-02-01T00:00', '20260201', '2026-00-10', '2026-01-00']) {
      expect(parseLocalDate(bad), bad).toBeNull();
    }
  });

  it('addDays: 월·연·윤년 경계와 음수', () => {
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2027-02-28', 1)).toBe('2027-03-01');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
    expect(addDays('2026-10-20', 0)).toBe('2026-10-20');
    expect(addDays('2026-01-31', 400)).toBe('2027-03-07');
  });

  it('today 는 주입한 시각 기준으로 서울 자정에 날짜가 바뀐다', () => {
    const seoulMidnight = Date.parse('2026-10-19T15:00:00Z');
    expect(today(seoulMidnight - 1, 'Asia/Seoul')).toBe('2026-10-19');
    expect(today(seoulMidnight, 'Asia/Seoul')).toBe('2026-10-20');
    expect(today(seoulMidnight, 'UTC')).toBe('2026-10-19');
    expect(today(seoulMidnight, 'Pacific/Kiritimati')).toBe('2026-10-20'); // UTC+14
  });

  it('시간대 검증', () => {
    expect(isValidZone('Asia/Seoul')).toBe(true);
    expect(isValidZone('Mars/Olympus')).toBe(false);
    expect(isValidZone('')).toBe(false);
  });

  it('DST: 하루가 23·25시간인 날의 자정과 날짜 변환', () => {
    // 미국 동부 2026-03-08 02:00 에 봄 시간 시작(23시간), 2026-11-01 에 가을 시간 종료(25시간)
    expect(startOfDay('2026-03-09', 'America/New_York').toString()).toBe('2026-03-09T04:00:00Z');
    expect(startOfDay('2026-11-01', 'America/New_York').toString()).toBe('2026-11-01T04:00:00Z');
    expect(startOfDay('2026-11-02', 'America/New_York').toString()).toBe('2026-11-02T05:00:00Z');
    expect(localDateOf(Instant.parse('2026-11-02T04:59:59Z')!, 'America/New_York')).toBe('2026-11-01');
  });

  it('DST: 자정이 존재하지 않는 날(브라질 상파울루 2018-11-04)은 그날의 첫 유효 시각(01:00)', () => {
    const start = startOfDay('2018-11-04', 'America/Sao_Paulo');
    expect(start.toString()).toBe('2018-11-04T03:00:00Z'); // 01:00 -02:00
    expect(localDateOf(start, 'America/Sao_Paulo')).toBe('2018-11-04');
    expect(localDateOf(Instant.ofEpochMilli(start.toEpochMilli() - 1), 'America/Sao_Paulo')).toBe('2018-11-03');
  });

  it('30분 단위 오프셋 시간대(인도 +05:30)', () => {
    expect(startOfDay('2026-10-20', 'Asia/Kolkata').toString()).toBe('2026-10-19T18:30:00Z');
    expect(localDateOf(Instant.parse('2026-10-19T18:29:59Z')!, 'Asia/Kolkata')).toBe('2026-10-19');
  });
});

describe('기간 파싱', () => {
  it.each([
    ['500ms', 500],
    ['30S', 30_000],
    ['15m', 900_000],
    ['1h', 3_600_000],
    ['7D', 604_800_000],
    ['-5s', -5000],
    ['+5s', 5000],
    ['1500us', 1.5],
    ['2000000ns', 2],
    ['0', 0],
    [' 10s ', 10_000],
    ['PT0.5S', 500],
    ['PT1H30M', 5_400_000],
    ['P1DT2H', 93_600_000],
    ['-PT30S', -30_000],
    ['PT1,5S', 1500],
    ['p2d', 172_800_000],
  ])('%j → %d ms', (text, ms) => {
    expect(parseDurationMs(text)).toBeCloseTo(ms);
  });

  it.each(['', 'P', 'PT', '1w', '1.5h', 'abc', '1 h', 'P1DT'])('잘못된 기간 %j 는 오류', (text) => {
    expect(() => parseDurationMs(text)).toThrow();
  });
});

describe('문구 서식(i18n)', () => {
  it('Accept-Language: q 정렬, 0 제외, 와일드카드·잘못된 q 무시, 대소문자 무시', () => {
    expect(resolveLocale('')).toBe('ko');
    expect(resolveLocale(null)).toBe('ko');
    expect(resolveLocale('EN')).toBe('en');
    expect(resolveLocale('en;q=0, ko;q=0.5')).toBe('ko');
    expect(resolveLocale('en;q=0')).toBe('ko');
    expect(resolveLocale('*')).toBe('ko');
    expect(resolveLocale('fr, en;q=0.2')).toBe('en');
    expect(resolveLocale('en;q=abc, ko')).toBe('ko'); // q 가 숫자가 아니면 항목 제외
    expect(resolveLocale('en-GB, fr')).toBe('en'); // 정확히 en/ko 가 없으면 언어가 맞는 첫 항목
    expect(resolveLocale('en-GB, ko')).toBe('ko'); // 정확한 ko 가 en-GB 보다 우선
    expect(resolveLocale('ko-KR;q=0.4, en;q=0.9')).toBe('en');
  });

  it('없는 키는 코드 자체, en 에 없으면 ko 로 대체', () => {
    expect(message('ko', 'no.such.code')).toBe('no.such.code');
    expect(message('en', 'no.such.code', 1)).toBe('no.such.code');
  });

  it('{n} 숫자는 천 단위 쉼표와 소수 3자리까지, number,# 는 반올림 정수', () => {
    expect(formatMessage('{0}', [1234567.8915], 'en')).toBe('1,234,567.892');
    expect(formatMessage('{0,number,#}', [2.5], 'en')).toBe('3');
    expect(formatMessage('{0,number,integer}', [12345.4], 'en')).toBe('12,345');
    expect(formatMessage('{0,number}', [1000], 'en')).toBe('1,000');
    expect(formatMessage('{0}', ['그냥 문자'], 'ko')).toBe('그냥 문자');
  });

  it('인자가 모자라면 자리표시자를 그대로 둔다', () => {
    expect(formatMessage('a {0} b {1}', ['x'], 'ko')).toBe('a x b {1}');
  });

  it("작은따옴표: '' 는 따옴표 하나, '...' 안은 서식 없이 그대로", () => {
    expect(formatMessage("It''s {0}", ['ok'], 'en')).toBe("It's ok");
    expect(formatMessage("'{0}' is {0}", ['v'], 'en')).toBe('{0} is v');
    expect(formatMessage("미종료 '{0}", ['v'], 'ko')).toBe('미종료 {0}');
  });

  it('choice: 경계값(#은 이상, <는 초과)과 중첩 서식', () => {
    const p = '{0,choice,0#없음|1#한 개|1<{0}개}';
    expect(formatMessage(p, [0], 'ko')).toBe('없음');
    expect(formatMessage(p, [1], 'ko')).toBe('한 개');
    expect(formatMessage(p, [1.5], 'ko')).toBe('1.5개');
    expect(formatMessage(p, [5], 'ko')).toBe('5개');
    expect(formatMessage(p, [-3], 'ko')).toBe('없음'); // 조건을 만족하는 항목이 없으면 첫 항목
    expect(formatMessage('{0,choice,-∞#음수|0#영 이상}', [-1], 'ko')).toBe('음수');
  });

  it('닫히지 않은 괄호·해석 못 하는 choice 는 오류(문구 데이터 오타를 조용히 넘기지 않는다)', () => {
    expect(() => formatMessage('{0', [1], 'ko')).toThrow();
    expect(() => formatMessage('{0,choice,garbage}', [1], 'ko')).toThrow();
  });

  it('ko·en 의 모든 문구가 인자를 넣어 서식화해도 오류가 없고, 같은 키는 같은 자리표시자를 쓴다', () => {
    const placeholders = (s: string) => [...s.matchAll(/\{(\d+)[,}]/g)].map((m) => m[1]).sort();
    for (const [code, ko] of Object.entries(KO)) {
      const en = EN[code]!;
      expect(() => formatMessage(ko, ['a', 'b', 3], 'ko'), code).not.toThrow();
      expect(() => formatMessage(en, ['a', 'b', 3], 'en'), code).not.toThrow();
      expect(new Set(placeholders(en)), `${code} 자리표시자`).toEqual(new Set(placeholders(ko)));
    }
  });
});

describe('crypto', () => {
  it('SHA-256 hex: 알려진 값, 한글은 UTF-8 로 해시', async () => {
    expect(await sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(await sha256Hex('한글')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('무작위 base64url: 32바이트는 43자·URL 안전·매번 다름', () => {
    const a = randomBase64Url(32);
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomBase64Url(32)).not.toBe(a);
    expect(randomBase64Url(1)).toHaveLength(2);
  });

  it('상수 시간 비교: 길이가 다르거나 한 글자만 달라도 false', () => {
    expect(timingSafeEqual('abc', 'abc')).toBe(true);
    expect(timingSafeEqual('abc', 'abd')).toBe(false);
    expect(timingSafeEqual('abc', 'ab')).toBe(false);
    expect(timingSafeEqual('', '')).toBe(true);
  });
});
