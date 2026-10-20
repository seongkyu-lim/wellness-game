// PKCE(RFC 7636) S256 도우미. 인가 코드가 가로채져도 code_verifier 없이는 토큰으로 교환할 수 없게 한다.

/** RFC 7636 4.1 권장 방식: 32바이트 난수를 base64url로 인코딩한 43자 code_verifier([A-Za-z0-9-_]). */
export function createCodeVerifier(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  return base64Url(bytes)
}

/** code_challenge = BASE64URL(SHA-256(ASCII(code_verifier))) (method S256) */
export async function codeChallengeS256(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

function base64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) {
    binary += String.fromCharCode(b)
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
