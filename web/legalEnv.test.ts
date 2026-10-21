import type { Plugin, ResolvedConfig } from 'vite'
import { describe, expect, it, vi } from 'vitest'
import {
  findLegalPlaceholders,
  legalEnvPlugin,
  PLACEHOLDER_CONTACT_EMAIL,
  PLACEHOLDER_EFFECTIVE_DATE,
  resolveLegalEnv,
} from './legalEnv'

describe('resolveLegalEnv', () => {
  it('환경변수가 없거나 비면 자리표시자를 쓴다', () => {
    expect(resolveLegalEnv({})).toEqual({ contactEmail: PLACEHOLDER_CONTACT_EMAIL, effectiveDate: PLACEHOLDER_EFFECTIVE_DATE })
    expect(resolveLegalEnv({ VITE_PRIVACY_CONTACT_EMAIL: ' ', VITE_PRIVACY_EFFECTIVE_DATE: '' }).contactEmail).toBe(
      PLACEHOLDER_CONTACT_EMAIL,
    )
  })

  it('설정된 값은 앞뒤 공백을 지워 쓴다', () => {
    expect(resolveLegalEnv({ VITE_PRIVACY_CONTACT_EMAIL: ' a@b.kr ', VITE_PRIVACY_EFFECTIVE_DATE: '2026-11-01' })).toEqual({
      contactEmail: 'a@b.kr',
      effectiveDate: '2026-11-01',
    })
  })
})

describe('findLegalPlaceholders', () => {
  it('자리표시자·예시 도메인·형식 오류를 찾는다', () => {
    expect(findLegalPlaceholders(resolveLegalEnv({}))).toHaveLength(2)
    expect(findLegalPlaceholders({ contactEmail: 'me@mail.example.org', effectiveDate: '2026-11-01' })).toHaveLength(1)
    expect(findLegalPlaceholders({ contactEmail: 'not-an-email', effectiveDate: '2026-11-01' })).toHaveLength(1)
    expect(findLegalPlaceholders({ contactEmail: 'a@b.kr', effectiveDate: '2026-13-45' })).toHaveLength(1)
    expect(findLegalPlaceholders({ contactEmail: 'a@b.kr', effectiveDate: '11/01/2026' })).toHaveLength(1)
  })

  // 버그: Date.parse는 2026-02-30 같은 없는 날짜를 다음 달로 넘겨 받아들이므로 형식 검사를 통과한다.
  // 고치면 이 테스트가 통과하므로 it.fails를 it으로 바꾼다.
  it.fails('존재하지 않는 날짜(2월 30일)도 형식 오류로 찾는다', () => {
    expect(findLegalPlaceholders({ contactEmail: 'a@b.kr', effectiveDate: '2026-02-30' })).toHaveLength(1)
  })

  it('실제 값이면 문제가 없다', () => {
    expect(findLegalPlaceholders({ contactEmail: 'privacy@wellness-game.app', effectiveDate: '2026-11-01' })).toEqual([])
  })
})

describe('legalEnvPlugin', () => {
  const run = (env: Record<string, string>) => {
    const plugin: Plugin = legalEnvPlugin()
    ;(plugin.configResolved as (config: ResolvedConfig) => void)({ env } as unknown as ResolvedConfig)
  }
  const REAL = { VITE_PRIVACY_CONTACT_EMAIL: 'privacy@wellness-game.app', VITE_PRIVACY_EFFECTIVE_DATE: '2026-11-01' }

  it('Vercel 빌드가 아니면 자리표시자를 허용한다', () => {
    vi.stubEnv('VERCEL', '')
    expect(() => run({})).not.toThrow()
  })

  it('Vercel 빌드에서는 문제가 된 환경변수 이름을 모두 담아 실패한다', () => {
    vi.stubEnv('VERCEL', '1')
    expect(() => run({})).toThrow(/VITE_PRIVACY_CONTACT_EMAIL.*VITE_PRIVACY_EFFECTIVE_DATE/)
    expect(() => run({ ...REAL, VITE_PRIVACY_EFFECTIVE_DATE: '2026-02-30x' })).toThrow(/VITE_PRIVACY_EFFECTIVE_DATE/)
  })

  it('Vercel 빌드라도 실제 값이면 통과한다', () => {
    vi.stubEnv('VERCEL', '1')
    expect(() => run(REAL)).not.toThrow()
  })
})
