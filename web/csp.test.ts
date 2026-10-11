import { describe, expect, it } from 'vitest'
import { buildCsp } from './csp'

function directives(policy: string): Map<string, string[]> {
  return new Map(
    policy.split('; ').map((d) => {
      const [name, ...sources] = d.split(' ')
      return [name, sources]
    }),
  )
}

describe('buildCsp', () => {
  it('connect-src에 API 오리진만(경로 제외) 넣는다', () => {
    const d = directives(buildCsp('https://api.example.com/v1/'))
    expect(d.get('connect-src')).toEqual(["'self'", 'https://api.example.com'])
  })

  it('API 주소가 비어 있으면 클라이언트 기본값(localhost:8080)을 쓴다', () => {
    expect(directives(buildCsp(undefined)).get('connect-src')).toEqual(["'self'", 'http://localhost:8080'])
    expect(directives(buildCsp('')).get('connect-src')).toEqual(["'self'", 'http://localhost:8080'])
  })

  it('스크립트는 self만, 인라인·eval을 허용하지 않는다', () => {
    const policy = buildCsp('https://api.example.com')
    expect(directives(policy).get('script-src')).toEqual(["'self'"])
    expect(policy).not.toContain('unsafe-inline')
    expect(policy).not.toContain('unsafe-eval')
    expect(directives(policy).get('object-src')).toEqual(["'none'"])
  })

  it('meta로 지정할 수 없는 frame-ancestors는 넣지 않는다', () => {
    expect(buildCsp(undefined)).not.toContain('frame-ancestors')
  })

  it('잘못된 API 주소는 빌드를 실패시킨다', () => {
    expect(() => buildCsp('not a url')).toThrow()
  })
})
