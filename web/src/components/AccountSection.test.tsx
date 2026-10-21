import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n/I18nProvider'
import { startLogin, type Session } from '../lib/auth'
import { AccountSection } from './AccountSection'

vi.mock('../lib/auth', () => ({
  isConfigured: () => true,
  startLogin: vi.fn(),
}))

const startLoginMock = vi.mocked(startLogin)

function renderSignedOut() {
  return render(
    <I18nProvider>
      <AccountSection session={null} onLogout={() => {}} />
    </I18nProvider>,
  )
}

beforeEach(() => {
  localStorage.setItem('wellness.lang', 'ko')
  startLoginMock.mockReset()
})

describe('AccountSection 로그인 시작', () => {
  it('로그인 시작 중에는 모든 로그인 버튼을 막는다', async () => {
    startLoginMock.mockReturnValue(new Promise(() => {}))
    renderSignedOut()
    await act(async () => screen.getByRole('button', { name: 'Google로 로그인' }).click())
    expect(startLoginMock).toHaveBeenCalledWith('google')
    for (const name of ['Kakao로 로그인', 'Naver로 로그인', 'Google로 로그인']) {
      expect(screen.getByRole<HTMLButtonElement>('button', { name }).disabled).toBe(true)
    }
  })

  it('로그인 시작이 실패하면(예: crypto.subtle 없음) 오류 배너를 보이고 버튼을 다시 연다', async () => {
    startLoginMock.mockRejectedValue(new TypeError('crypto.subtle is undefined'))
    renderSignedOut()
    await act(async () => screen.getByRole('button', { name: 'Kakao로 로그인' }).click())
    expect(screen.getByRole('alert').textContent).toContain('로그인을 시작할 수 없습니다.')
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Kakao로 로그인' }).disabled).toBe(false)
  })
})

const SESSION: Session = {
  provider: 'naver',
  userId: 'naver:42',
  displayName: null,
  accessToken: 'tok',
  expiresAt: Date.now() + 60_000,
}

describe('AccountSection 로그인 상태', () => {
  it('provider·사용자 ID를 보이고, 이름이 없으면 기본 호칭을 쓰며 로그인 버튼은 숨긴다', () => {
    render(
      <I18nProvider>
        <AccountSection session={SESSION} onLogout={() => {}} />
      </I18nProvider>,
    )
    expect(screen.getByText('Naver 로그인')).toBeTruthy()
    expect(screen.getByText('naver:42')).toBeTruthy()
    expect(document.body.textContent).toContain('회원님,')
    expect(screen.queryByRole('button', { name: /로 로그인$/ })).toBeNull()
  })

  it('로그아웃 버튼은 onLogout만 부르고 로그인을 시작하지 않는다', () => {
    const onLogout = vi.fn()
    render(
      <I18nProvider>
        <AccountSection session={{ ...SESSION, displayName: '홍길동' }} onLogout={onLogout} />
      </I18nProvider>,
    )
    expect(document.body.textContent).toContain('홍길동님,')
    act(() => screen.getByRole('button', { name: '로그아웃' }).click())
    expect(onLogout).toHaveBeenCalledTimes(1)
    expect(startLoginMock).not.toHaveBeenCalled()
  })
})

describe('AccountSection 언어별 로그인 버튼 배치', () => {
  const buttonNames = (root: Element) => Array.from(root.querySelectorAll('button')).map((b) => b.textContent)

  it('한국어는 카카오·네이버·구글 순으로 모두 펼쳐 보인다', () => {
    renderSignedOut()
    expect(document.querySelector('details')).toBeNull()
    expect(buttonNames(document.body)).toEqual(['Kakao로 로그인', 'Naver로 로그인', 'Google로 로그인'])
  })

  it('영어는 구글만 펼치고 카카오·네이버는 "다른 방법" 아래에 접어 둔다', () => {
    localStorage.setItem('wellness.lang', 'en')
    renderSignedOut()
    const details = document.querySelector('details')!
    expect(details.querySelector('summary')?.textContent).toBe('Other sign-in options')
    expect(buttonNames(details)).toEqual(['Sign in with Kakao', 'Sign in with Naver'])
    expect(buttonNames(document.body)[0]).toBe('Sign in with Google')
  })
})
