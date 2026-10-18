import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  detectLanguage,
  format,
  getLanguage,
  intlLocale,
  LocalizedError,
  saveLanguage,
  setLanguage,
  t,
  toMessage,
  translate,
  translateMessage,
} from './core'

function stubNavigatorLanguage(value: string) {
  vi.spyOn(navigator, 'language', 'get').mockReturnValue(value)
}

describe('detectLanguage', () => {
  beforeEach(() => localStorage.clear())

  it('localStorage 저장값을 navigator.language보다 우선한다', () => {
    stubNavigatorLanguage('ko-KR')
    saveLanguage('en')
    expect(detectLanguage()).toBe('en')
  })

  it('저장값이 없으면 navigator.language가 ko로 시작할 때 ko', () => {
    stubNavigatorLanguage('ko-KR')
    expect(detectLanguage()).toBe('ko')
  })

  it('저장값이 없고 ko가 아니면 en', () => {
    stubNavigatorLanguage('fr-FR')
    expect(detectLanguage()).toBe('en')
  })

  it('잘못된 저장값은 무시하고 navigator.language로 넘어간다', () => {
    localStorage.setItem('wellness.lang', 'ja')
    stubNavigatorLanguage('KO')
    expect(detectLanguage()).toBe('ko')
  })

  it('localStorage 접근이 막히면 navigator.language를 쓴다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    stubNavigatorLanguage('en-US')
    expect(detectLanguage()).toBe('en')
  })
})

describe('translate / format', () => {
  afterEach(() => setLanguage('ko'))

  it('언어별 사전을 쓴다', () => {
    expect(translate('ko', 'error.loginFailed')).toBe('로그인에 실패했습니다.')
    expect(translate('en', 'error.loginFailed')).toBe('Sign-in failed.')
  })

  it('파라미터를 치환한다', () => {
    expect(translate('en', 'error.request', { status: 503 })).toBe('Request failed (503).')
    expect(translate('ko', 'error.request', { status: 500 })).toBe('요청에 실패했습니다. (500)')
  })

  it('없는 파라미터는 자리표시자를 그대로 둔다', () => {
    expect(format('{a} and {b}', { a: 1 })).toBe('1 and {b}')
    expect(format('{a}')).toBe('{a}')
  })

  it('React 밖의 t()는 setLanguage로 정한 현재 언어를 따른다', () => {
    setLanguage('en')
    expect(getLanguage()).toBe('en')
    expect(t('error.loginFailed')).toBe('Sign-in failed.')
    setLanguage('ko')
    expect(t('error.loginFailed')).toBe('로그인에 실패했습니다.')
  })
})

describe('Message', () => {
  it('키 메시지는 언어마다 번역하고 raw는 그대로 둔다', () => {
    const keyed = { key: 'error.request', params: { status: 401 } } as const
    expect(translateMessage('ko', keyed)).toBe('요청에 실패했습니다. (401)')
    expect(translateMessage('en', keyed)).toBe('Request failed (401).')
    expect(translateMessage('en', { raw: '서버 문구' })).toBe('서버 문구')
  })

  it('toMessage는 LocalizedError의 메시지를, 그 밖의 에러는 대체 키를 돌려준다', () => {
    expect(toMessage(new LocalizedError({ key: 'error.noToken' }), 'error.network')).toEqual({ key: 'error.noToken' })
    expect(toMessage(new TypeError('Failed to fetch'), 'error.network')).toEqual({ key: 'error.network' })
    expect(toMessage('boom', 'error.loginFailed')).toEqual({ key: 'error.loginFailed' })
  })
})

describe('intlLocale', () => {
  it('ko→ko-KR, en→en-US', () => {
    expect(intlLocale('ko')).toBe('ko-KR')
    expect(intlLocale('en')).toBe('en-US')
  })
})
