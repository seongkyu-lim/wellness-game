import { describe, expect, it } from 'vitest'
import { APP_ROUTES } from './src/legal/legal'
import vercel from './vercel.json'

describe('vercel.json 라우팅', () => {
  it('SPA 리라이트는 앱 경로만 index.html로 보낸다(없는 경로는 404)', () => {
    // '/'(소셜 로그인 콜백 /?code= 포함)는 정적 index.html이 그대로 응답하므로 리라이트가 필요 없다.
    const sources = vercel.rewrites.map((r) => r.source)
    expect(['/', ...sources].sort()).toEqual([...APP_ROUTES].sort())
    for (const rewrite of vercel.rewrites) {
      expect(rewrite.destination).toBe('/index.html')
      // 와일드카드·정규식 패턴이 없어야 임의 경로가 200 + index.html이 되지 않는다.
      expect(rewrite.source).toMatch(/^\/[a-z-]+$/)
    }
  })

  it('모든 경로에 클릭재킹 방지·보안 헤더를 붙인다', () => {
    const all = vercel.headers.find((h) => h.source === '/(.*)')
    const keys = all?.headers.map((h) => h.key)
    expect(all?.headers.find((h) => h.key === 'Content-Security-Policy')?.value).toBe("frame-ancestors 'none'")
    expect(keys).toEqual(
      expect.arrayContaining(['X-Content-Type-Options', 'Referrer-Policy', 'Permissions-Policy', 'X-Frame-Options']),
    )
  })
})
