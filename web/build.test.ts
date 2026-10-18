// @vitest-environment node
import { build, type Rollup } from 'vite'
import { describe, expect, it, vi } from 'vitest'

// 실제 `vite build`(프로덕션 모드)를 메모리에서 돌려(write: false) 산출물 index.html을 검사한다.
const ROOT = decodeURIComponent(new URL('.', import.meta.url).pathname)

const REAL_LEGAL = { email: 'privacy@wellness-game.app', date: '2026-11-01' }

async function buildIndexHtml(
  apiBaseUrl: string,
  { vercel = false, legal = { email: '', date: '' } } = {},
): Promise<string> {
  vi.stubEnv('VITE_API_BASE_URL', apiBaseUrl)
  // CI·로컬 환경변수에 영향받지 않도록 VERCEL·개인정보 값을 항상 명시한다.
  vi.stubEnv('VERCEL', vercel ? '1' : '')
  vi.stubEnv('VITE_PRIVACY_CONTACT_EMAIL', legal.email)
  vi.stubEnv('VITE_PRIVACY_EFFECTIVE_DATE', legal.date)
  const result = (await build({
    root: ROOT,
    mode: 'production',
    logLevel: 'silent',
    build: { write: false },
  })) as Rollup.RollupOutput
  const html = result.output.find((o) => o.fileName === 'index.html')
  if (!html || html.type !== 'asset') {
    throw new Error('index.html 산출물이 없습니다')
  }
  return String(html.source)
}

function cspOf(html: string): string | null {
  return html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)"/)?.[1]?.replaceAll('&#39;', "'") ?? null
}

describe('빌드 산출물 index.html', () => {
  it('CSP meta가 있고 폰트·스크립트·스타일 로드보다 앞에 온다', async () => {
    const html = await buildIndexHtml('https://api.example.com/base')
    const csp = cspOf(html)
    expect(csp).toContain("connect-src 'self' https://api.example.com;")
    expect(csp).toContain("script-src 'self';")

    const metaAt = html.indexOf('http-equiv="Content-Security-Policy"')
    const fontAt = html.indexOf('https://fonts.googleapis.com/css2')
    const scriptAt = html.indexOf('<script')
    expect(fontAt).toBeGreaterThan(0)
    expect(metaAt).toBeGreaterThan(0)
    expect(metaAt).toBeLessThan(fontAt)
    expect(metaAt).toBeLessThan(scriptAt)
  }, 30_000)

  it('프로덕션에서 API 주소가 비어 있으면 connect-src에 localhost를 넣지 않는다', async () => {
    const csp = cspOf(await buildIndexHtml(''))
    expect(csp).toContain("connect-src 'self';")
    expect(csp).not.toContain('localhost')
  }, 30_000)

  it('Vercel 빌드(VERCEL=1)에서 API 주소가 비어 있으면 빌드가 실패한다', async () => {
    await expect(buildIndexHtml('', { vercel: true })).rejects.toThrow(/VITE_API_BASE_URL/)
  }, 30_000)

  it('Vercel 빌드에서 개인정보 처리방침 자리표시자가 남아 있으면 빌드가 실패한다', async () => {
    await expect(buildIndexHtml('https://api.example.com', { vercel: true })).rejects.toThrow(
      /VITE_PRIVACY_CONTACT_EMAIL.*VITE_PRIVACY_EFFECTIVE_DATE/,
    )
    await expect(
      buildIndexHtml('https://api.example.com', { vercel: true, legal: { ...REAL_LEGAL, date: 'YYYY-MM-DD' } }),
    ).rejects.toThrow(/VITE_PRIVACY_EFFECTIVE_DATE/)
  }, 30_000)

  it('로컬 빌드는 개인정보 처리방침 자리표시자를 허용한다', async () => {
    expect(cspOf(await buildIndexHtml('https://api.example.com'))).not.toBeNull()
  }, 30_000)

  it('Vercel 빌드라도 API 주소와 실제 개인정보 값이 있으면 통과한다', async () => {
    expect(cspOf(await buildIndexHtml('https://api.example.com', { vercel: true, legal: REAL_LEGAL }))).toContain(
      "connect-src 'self' https://api.example.com;",
    )
  }, 30_000)
})
