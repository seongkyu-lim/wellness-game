// @vitest-environment node
import { build, type Rollup } from 'vite'
import { describe, expect, it, vi } from 'vitest'

// 실제 `vite build`(프로덕션 모드)를 메모리에서 돌려(write: false) 산출물 index.html을 검사한다.
const ROOT = decodeURIComponent(new URL('.', import.meta.url).pathname)

async function buildIndexHtml(apiBaseUrl: string): Promise<string> {
  vi.stubEnv('VITE_API_BASE_URL', apiBaseUrl)
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
})
