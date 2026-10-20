/** Web Crypto 만 쓴다(Node 22·Workers 공통). */

/** UTF-8 문자열의 SHA-256 소문자 hex. 리프레시 토큰 저장 해시·Apple nonce 비교(Spring Sha256.hex 와 같음) */
export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** n 바이트 난수의 base64url(패딩 없음). 리프레시 토큰 원문(32바이트 → 43자) */
export function randomBase64Url(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64UrlEncode(buffer);
}

export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomUuid(): string {
  return crypto.randomUUID();
}

/** 길이가 같은 문자열의 상수 시간 비교(MessageDigest.isEqual) */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
