import { describe, expect, it } from 'vitest'
import { buildCsp, isVercelBuild, resolveApiOrigin } from './csp'

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
