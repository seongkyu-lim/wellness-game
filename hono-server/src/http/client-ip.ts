import { IPV4 } from '../lib/patterns.js';

export const MAX_ADDRESS_LENGTH = 64;

/** X-Forwarded-For 가 여러 줄로 오면 어느 값도 믿지 않고 모두 이 키 하나로 센다(위조 줄을 섞어 키를 늘리지 못하게) */
export const MULTIPLE_FORWARDED_LINES_KEY = 'multi-xff';

/**
 * 레이트 리밋·로그인 잠금 키로 쓸 클라이언트 IP(Spring ClientIpResolver + #102).
 *
 * - Workers: Cloudflare 가 넣는 CF-Connecting-IP(클라이언트가 위조할 수 없음)
 * - Node: 기본은 소켓 주소. APP_RATE_LIMIT_TRUST_FORWARDED_HEADER=true 면 X-Forwarded-For 에서 오른쪽부터
 *   APP_RATE_LIMIT_TRUSTED_PROXY_HOPS 개를 건너뛴 값을 쓴다(0 = 마지막 값 = 바로 앞단 프록시가 덧붙인 주소).
 *   항목이 모자라면 소켓 주소로 돌아간다. 헤더가 여러 줄이면 고정 공용 키를 쓴다.
 * - IP 리터럴이 아니거나 64자를 넘으면 버리고 다음 후보(소켓 주소)로 간다. IPv6 는 /64 접두사로 묶는다.
 */
export function resolveClientIp(options: {
  platform: 'node' | 'workers';
  cfConnectingIp?: string | null;
  /** X-Forwarded-For 헤더 줄들(원문 순서) */
  forwardedForLines?: string[];
  remoteAddress?: string | null;
  trustForwardedHeader: boolean;
  trustedProxyHops: number;
}): string {
  if (options.platform === 'workers') {
    const cf = normalizeIp(options.cfConnectingIp);
    if (cf) return cf;
  } else if (options.trustForwardedHeader) {
    const lines = options.forwardedForLines ?? [];
    if (lines.length > 1) return MULTIPLE_FORWARDED_LINES_KEY;
    const forwarded = normalizeIp(forwardedAddress(lines[0], options.trustedProxyHops));
    if (forwarded) return forwarded;
  }
  const remote = options.remoteAddress ?? null;
  const normalized = normalizeIp(remote);
  if (normalized) return normalized;
  return !remote || !remote.trim() ? 'unknown' : remote.slice(0, MAX_ADDRESS_LENGTH);
}

function forwardedAddress(header: string | null | undefined, hops: number): string | null {
  if (!header || hops < 0) return null;
  const parts = header.split(',');
  const index = parts.length - 1 - hops;
  return index < 0 ? null : (parts[index] ?? null);
}

/** IP 리터럴이면 키 형식(IPv4 그대로, IPv4-mapped IPv6 는 IPv4 로, IPv6 는 "xxxx:xxxx:xxxx:xxxx::/64")으로, 아니면 null */
export function normalizeIp(candidate: string | null | undefined): string | null {
  if (candidate === null || candidate === undefined) return null;
  let value = candidate.trim();
  if (value.startsWith('[') && value.endsWith(']')) value = value.slice(1, -1);
  if (!value || value.length > MAX_ADDRESS_LENGTH) return null;
  if (IPV4.test(value)) {
    return value.split('.').every((octet) => Number(octet) <= 255) ? value : null;
  }
  if (!value.includes(':') || !/^[0-9A-Fa-f:.]+$/.test(value)) return null;
  const groups = parseIpv6(value);
  if (!groups) return null;
  // ::ffff:a.b.c.d → IPv4 (JDK InetAddress 도 IPv4 로 돌려준다)
  if (groups.slice(0, 5).every((g) => g === 0) && groups[5] === 0xffff) {
    return `${groups[6]! >> 8}.${groups[6]! & 0xff}.${groups[7]! >> 8}.${groups[7]! & 0xff}`;
  }
  return groups
    .slice(0, 4)
    .map((g) => g.toString(16))
    .join(':')
    .concat('::/64');
}

/** IPv6 텍스트를 16비트 그룹 8개로. 틀리면 null */
function parseIpv6(value: string): number[] | null {
  const lastColon = value.lastIndexOf(':');
  const last = value.slice(lastColon + 1);
  if (last.includes('.')) {
    // 끝의 IPv4 표기(예: ::ffff:1.2.3.4)는 두 그룹으로 바꿔 다시 해석한다
    if (!IPV4.test(last)) return null;
    const octets = last.split('.').map(Number) as [number, number, number, number];
    if (octets.some((o) => o > 255)) return null;
    const hex = (hi: number, lo: number) => ((hi << 8) | lo).toString(16);
    return parseIpv6(`${value.slice(0, lastColon + 1)}${hex(octets[0], octets[1])}:${hex(octets[2], octets[3])}`);
  }
  const doubleColon = value.indexOf('::');
  if (doubleColon !== value.lastIndexOf('::')) return null;
  const parseGroups = (s: string) => (s === '' ? [] : s.split(':'));
  let head: string[];
  let rest: string[];
  if (doubleColon >= 0) {
    head = parseGroups(value.slice(0, doubleColon));
    rest = parseGroups(value.slice(doubleColon + 2));
    if (head.length + rest.length > 7) return null;
  } else {
    head = value.split(':');
    rest = [];
    if (head.length !== 8) return null;
  }
  const all = [...head, ...Array(8 - head.length - rest.length).fill('0'), ...rest];
  if (all.length !== 8 || all.some((g) => !/^[0-9A-Fa-f]{1,4}$/.test(g))) return null;
  return all.map((g) => parseInt(g, 16));
}
