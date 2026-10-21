import type { HtmlTagDescriptor, Plugin, ResolvedConfig } from 'vite'
import { describe, expect, it, vi } from 'vitest'
import { buildCsp, cspPlugin, isVercelBuild, resolveApiOrigin } from './csp'

function directives(policy: string): Map<string, string[]> {
  return new Map(
    policy.split('; ').map((d) => {
      const [name, ...sources] = d.split(' ')
      return [name, sources]
    }),
  )
}

describe('resolveApiOrigin', () => {
  it('설정된 API 주소는 오리진만(경로 제외) 쓴다', () => {
    expect(resolveApiOrigin('https://api.example.com/v1/', 'production')).toBe('https://api.example.com')
  })

  it('주소가 비어 있으면 개발 빌드는 localhost 기본값, 프로덕션은 null', () => {
    expect(resolveApiOrigin(undefined, 'development')).toBe('http://localhost:8080')
    expect(resolveApiOrigin('', 'test')).toBe('http://localhost:8080')
    expect(resolveApiOrigin('', 'production')).toBeNull()
    expect(resolveApiOrigin(undefined, 'production')).toBeNull()
  })

  it('잘못된 API 주소는 빌드를 실패시킨다', () => {
    expect(() => resolveApiOrigin('not a url', 'production')).toThrow()
  })
})

describe('buildCsp', () => {
  it('connect-src에 API 오리진을 넣고, 없으면 self만 둔다', () => {
    expect(directives(buildCsp('https://api.example.com')).get('connect-src')).toEqual([
      "'self'",
      'https://api.example.com',
    ])
    expect(directives(buildCsp(null)).get('connect-src')).toEqual(["'self'"])
  })

  it('스크립트는 self만, 인라인·eval을 허용하지 않는다', () => {
    const policy = buildCsp('https://api.example.com')
    expect(directives(policy).get('script-src')).toEqual(["'self'"])
    expect(policy).not.toContain('unsafe-inline')
    expect(policy).not.toContain('unsafe-eval')
    expect(directives(policy).get('object-src')).toEqual(["'none'"])
  })

  it('meta로 지정할 수 없는 frame-ancestors는 넣지 않는다', () => {
    expect(buildCsp(null)).not.toContain('frame-ancestors')
  })
})

describe('isVercelBuild', () => {
  it('VERCEL=1일 때만 Vercel 빌드로 본다', () => {
    expect(isVercelBuild({ VERCEL: '1' })).toBe(true)
    expect(isVercelBuild({ VERCEL: '' })).toBe(false)
    expect(isVercelBuild({})).toBe(false)
  })
})

/** 빌드 없이 플러그인 훅만 불러 본다. 실제 vite build 검증은 build.test.ts가 맡는다. */
function runCspPlugin(env: Record<string, string>, mode = 'production') {
  const plugin: Plugin = cspPlugin()
  const warn = vi.fn()
  const config = { env, mode, logger: { warn } } as unknown as ResolvedConfig
  ;(plugin.configResolved as (config: ResolvedConfig) => void)(config)
  const tags = (plugin.transformIndexHtml as () => HtmlTagDescriptor[])()
  return { plugin, warn, tags }
}

describe('cspPlugin', () => {
  it('빌드에만 적용된다(개발 서버의 HMR 인라인 스크립트를 막지 않기 위해)', () => {
    expect(cspPlugin().apply).toBe('build')
  })

  it('API 오리진을 connect-src에 넣은 CSP meta를 head 맨 앞에 주입한다', () => {
    const { tags, warn } = runCspPlugin({ VITE_API_BASE_URL: 'https://api.wellness.app/v1' })
    expect(tags).toHaveLength(1)
    expect(tags[0]).toMatchObject({ tag: 'meta', injectTo: 'head-prepend', attrs: { 'http-equiv': 'Content-Security-Policy' } })
    const policy = directives(String(tags[0].attrs!.content))
    expect(policy.get('connect-src')).toEqual(["'self'", 'https://api.wellness.app'])
    expect(warn).not.toHaveBeenCalled()
  })

  it('로컬 프로덕션 빌드에서 API 주소가 비면 경고만 하고 connect-src를 self로 제한한다', () => {
    vi.stubEnv('VERCEL', '')
    const { tags, warn } = runCspPlugin({})
    expect(warn).toHaveBeenCalledTimes(1)
    expect(directives(String(tags[0].attrs!.content)).get('connect-src')).toEqual(["'self'"])
  })

  it('Vercel 빌드에서 API 주소가 비면 빌드를 실패시킨다', () => {
    vi.stubEnv('VERCEL', '1')
    expect(() => runCspPlugin({})).toThrow(/VITE_API_BASE_URL/)
  })

  it('개발 모드 설정이면 API 주소가 없어도 localhost 기본값을 허용한다', () => {
    vi.stubEnv('VERCEL', '1')
    const { tags, warn } = runCspPlugin({}, 'development')
    expect(warn).not.toHaveBeenCalled()
    expect(directives(String(tags[0].attrs!.content)).get('connect-src')).toEqual(["'self'", 'http://localhost:8080'])
  })
})
