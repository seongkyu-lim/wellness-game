import { describe, expect, it } from 'vitest'
import { getLoginProviderLayout } from './loginProviders'

describe('getLoginProviderLayout', () => {
  it('ko는 카카오·네이버·구글을 모두 펼쳐 보인다', () => {
    expect(getLoginProviderLayout('ko')).toEqual({ primary: ['kakao', 'naver', 'google'], secondary: [] })
  })

  it('en은 구글만 펼치고 카카오·네이버는 접힌 영역에 둔다', () => {
    expect(getLoginProviderLayout('en')).toEqual({ primary: ['google'], secondary: ['kakao', 'naver'] })
  })

  it('언어마다 모든 provider가 정확히 한 번씩 나온다', () => {
    for (const lang of ['ko', 'en'] as const) {
      const { primary, secondary } = getLoginProviderLayout(lang)
      expect([...primary, ...secondary].sort()).toEqual(['google', 'kakao', 'naver'])
    }
  })
})
