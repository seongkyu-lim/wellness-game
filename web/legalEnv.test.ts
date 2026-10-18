import { describe, expect, it } from 'vitest'
import { findLegalPlaceholders, PLACEHOLDER_CONTACT_EMAIL, PLACEHOLDER_EFFECTIVE_DATE, resolveLegalEnv } from './legalEnv'

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

  it('실제 값이면 문제가 없다', () => {
    expect(findLegalPlaceholders({ contactEmail: 'privacy@wellness-game.app', effectiveDate: '2026-11-01' })).toEqual([])
  })
})
