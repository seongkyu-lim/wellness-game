import { describe, expect, it } from 'vitest'
import { codeChallengeS256, createCodeVerifier } from './pkce'

describe('PKCE', () => {
  it('code_verifier는 43~128자의 unreserved 문자열이고 매번 다르다', () => {
    const a = createCodeVerifier()
    const b = createCodeVerifier()
    expect(a).toMatch(/^[A-Za-z0-9\-._~]{43,128}$/)
    expect(b).toMatch(/^[A-Za-z0-9\-._~]{43,128}$/)
    expect(a).not.toBe(b)
  })

  it('RFC 7636 부록 B 테스트 벡터와 같은 S256 code_challenge를 만든다', async () => {
    await expect(codeChallengeS256('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).resolves.toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    )
  })
})
