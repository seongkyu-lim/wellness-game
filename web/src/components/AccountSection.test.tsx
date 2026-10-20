import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n/I18nProvider'
import { startLogin } from '../lib/auth'
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
