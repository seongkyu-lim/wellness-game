import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { normalizeIp, resolveClientIp, MULTIPLE_FORWARDED_LINES_KEY } from '../../src/http/client-ip.js';
import { addDays, localDateOf, startOfDay } from '../../src/lib/dates.js';
import { parseDurationMs } from '../../src/lib/duration.js';
import { message, resolveLocale } from '../../src/lib/i18n.js';
import { Instant } from '../../src/lib/instant.js';
import { EN, KO } from '../../src/lib/messages-data.js';

describe('Instant(java.time.Instant 호환)', () => {
  it('toString 은 Java 와 같다(소수부 0 생략, 3·6·9 자리)', () => {
    expect(Instant.parse('2026-10-20T01:02:03Z')!.toString()).toBe('2026-10-20T01:02:03Z');
    expect(Instant.parse('2026-10-20T01:02:03.000Z')!.toString()).toBe('2026-10-20T01:02:03Z');
    expect(Instant.parse('2026-10-20T01:02:03.1Z')!.toString()).toBe('2026-10-20T01:02:03.100Z');
    expect(Instant.parse('2026-10-20T01:02:03.1234Z')!.toString()).toBe('2026-10-20T01:02:03.123400Z');
    expect(Instant.parse('2026-10-20T01:02:03.123456789Z')!.toString()).toBe('2026-10-20T01:02:03.123456789Z');
    expect(Instant.parse('2026-10-20T10:02+09:00')!.toString()).toBe('2026-10-20T01:02:00Z');
  });

  it('잘못된 형식·오프셋 없는 시각은 null', () => {
    expect(Instant.parse('2026-10-20T01:02:03')).toBeNull();
    expect(Instant.parse('2026-02-30T00:00:00Z')).toBeNull();
    expect(Instant.parse('yesterday')).toBeNull();
  });
});

describe('날짜·시간대', () => {
  it('서울 기준 날짜와 자정', () => {
    expect(localDateOf(Instant.parse('2026-10-19T15:00:00Z')!, 'Asia/Seoul')).toBe('2026-10-20');
    expect(localDateOf(Instant.parse('2026-10-19T14:59:59Z')!, 'Asia/Seoul')).toBe('2026-10-19');
    expect(startOfDay('2026-10-20', 'Asia/Seoul').toString()).toBe('2026-10-19T15:00:00Z');
    expect(startOfDay('2026-03-08', 'America/New_York').toString()).toBe('2026-03-08T05:00:00Z');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });

  it('Spring Duration 형식', () => {
    expect(parseDurationMs('30s')).toBe(30_000);
    expect(parseDurationMs('7d')).toBe(7 * 86_400_000);
    expect(parseDurationMs('PT1H')).toBe(3_600_000);
    expect(parseDurationMs('500')).toBe(500);
    expect(() => parseDurationMs('soon')).toThrow();
  });
});

describe('문구(ko/en)', () => {
  it('ko·en 키 집합이 같고, 이 브랜치의 Spring properties 키를 모두 담는다', () => {
    expect(Object.keys(EN).sort()).toEqual(Object.keys(KO).sort());
    const spring = readFileSync(new URL('../../../backend/src/main/resources/messages.properties', import.meta.url), 'utf8')
      .split('\n')
      .filter((l) => l.includes('=') && !l.trimStart().startsWith('#'))
      .map((l) => l.slice(0, l.indexOf('=')).trim());
    for (const key of spring) expect(KO, key).toHaveProperty([key]);
  });

  it('Accept-Language 해석은 Spring AcceptHeaderLocaleResolver 와 같다', () => {
    expect(resolveLocale(undefined)).toBe('ko');
    expect(resolveLocale('en-US,en;q=0.9')).toBe('en');
    expect(resolveLocale('en-US, ko')).toBe('ko');
    expect(resolveLocale('fr-FR')).toBe('ko');
    expect(resolveLocale('ko;q=0.1, en;q=0.9')).toBe('en');
  });

  it('MessageFormat: 숫자 서식·choice', () => {
    expect(message('ko', 'error.rate-limited', 42)).toBe('요청이 너무 많아요. 42초 후에 다시 시도해 주세요.');
    expect(message('en', 'error.rate-limited', 1)).toBe('Too many requests. Please try again in 1 second.');
    expect(message('en', 'error.rate-limited', 30)).toBe('Too many requests. Please try again in 30 seconds.');
    expect(message('en', 'sync.error.date-out-of-range', 1)).toBe('The sync date must be within 1 day of today.');
    expect(message('ko', 'activity.reward.workout', '수영', 130)).toBe('수영 완료 +130 XP');
  });
});

describe('클라이언트 IP', () => {
  const base = { platform: 'node' as const, remoteAddress: '10.0.0.9', trustForwardedHeader: true, trustedProxyHops: 0 };

  it('IPv4 그대로, IPv6 는 /64, 그 밖은 null', () => {
    expect(normalizeIp('203.0.113.7')).toBe('203.0.113.7');
    expect(normalizeIp('2001:db8:1:2:3:4:5:6')).toBe('2001:db8:1:2::/64');
    expect(normalizeIp('[2001:db8::1]')).toBe('2001:db8:0:0::/64');
    expect(normalizeIp('::ffff:198.51.100.1')).toBe('198.51.100.1');
    expect(normalizeIp('999.1.1.1')).toBeNull();
    expect(normalizeIp('evil.example.com')).toBeNull();
  });

  it('Node: 신뢰 설정이 없으면 소켓 주소, 있으면 오른쪽에서 hops 개 건너뛴 값', () => {
    expect(resolveClientIp({ ...base, trustForwardedHeader: false, forwardedForLines: ['1.1.1.1'] })).toBe('10.0.0.9');
    expect(resolveClientIp({ ...base, forwardedForLines: ['6.6.6.6, 1.1.1.1'] })).toBe('1.1.1.1');
    expect(resolveClientIp({ ...base, trustedProxyHops: 1, forwardedForLines: ['6.6.6.6, 1.1.1.1, 10.1.1.1'] })).toBe('1.1.1.1');
    expect(resolveClientIp({ ...base, trustedProxyHops: 3, forwardedForLines: ['1.1.1.1, 10.1.1.1'] })).toBe('10.0.0.9');
  });

  it('Node: X-Forwarded-For 가 여러 줄이면 고정 공용 키', () => {
    expect(resolveClientIp({ ...base, forwardedForLines: ['1.1.1.1', '2.2.2.2'] })).toBe(MULTIPLE_FORWARDED_LINES_KEY);
  });

  it('Workers: CF-Connecting-IP', () => {
    expect(resolveClientIp({ ...base, platform: 'workers', cfConnectingIp: '198.51.100.4', forwardedForLines: ['6.6.6.6'] })).toBe(
      '198.51.100.4',
    );
  });
});
