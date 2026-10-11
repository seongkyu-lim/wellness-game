import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { I18nProvider } from './i18n/I18nProvider'

const SESSION = {
  provider: 'kakao',
  userId: 'kakao:1',
  displayName: '홍길동',
  accessToken: 'tok',
  expiresAt: Date.now() + 3_600_000,
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status })
}

function renderApp() {
  return render(
    <I18nProvider>
      <App />
    </I18nProvider>,
  )
}

const switchTo = (label: '한' | 'EN') => act(() => screen.getByRole('button', { name: label }).click())

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  localStorage.setItem('wellness.lang', 'ko')
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

describe('App 배너와 언어 전환', () => {
  it('세션 만료(401)면 세션을 지우고, 만료 배너는 언어를 바꾸면 다시 번역된다', async () => {
    localStorage.setItem('wellness.session', JSON.stringify(SESSION))
    fetchMock.mockImplementation(async () => json(401, { message: '인증이 필요합니다.' }))
    renderApp()

    expect(await screen.findByRole('alert')).toHaveProperty('textContent', '로그인이 만료되었습니다. 다시 로그인해 주세요.')
    expect(localStorage.getItem('wellness.session')).toBeNull()

    switchTo('EN')
    expect(screen.getByRole('alert').textContent).toBe('Your session has expired. Please sign in again.')
  })

  it('네트워크 실패(fetch TypeError)는 error.network 배너로 보이고 재번역된다', async () => {
    localStorage.setItem('wellness.session', JSON.stringify(SESSION))
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    renderApp()

    expect((await screen.findByRole('alert')).textContent).toBe(
      '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.',
    )
    switchTo('EN')
    expect(screen.getByRole('alert').textContent).toBe("Can't reach the server. Make sure the backend is running.")
  })

  it('언어를 바꿔도 데이터를 다시 조회하지 않는다', async () => {
    localStorage.setItem('wellness.session', JSON.stringify(SESSION))
    fetchMock.mockImplementation(async (url: string) =>
      url.includes('/api/characters/me')
        ? json(404, {})
        : json(200, { userId: SESSION.userId, date: '2026-01-01', activities: [] }),
    )
    renderApp()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    // StrictMode가 아니므로 첫 조회는 캐릭터·활동 2건이다.
    switchTo('EN')
    switchTo('한')
    await act(async () => {
      await Promise.resolve()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('로그인 리다이렉트 state 불일치 배너도 언어 전환 시 재번역된다', async () => {
    sessionStorage.setItem('wellness.oauth-state', 'kakao:expected')
    window.history.replaceState(null, '', '/?code=c&state=kakao:forged')
    renderApp()

    expect((await screen.findByRole('alert')).textContent).toBe('로그인 상태 검증에 실패했습니다. 다시 시도해 주세요.')
    switchTo('EN')
    expect(screen.getByRole('alert').textContent).toBe("We couldn't verify your sign-in. Please try again.")
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('로그인 교환이 서버 메시지 없이 실패하면 상태 코드 배너를 언어에 맞게 보인다', async () => {
    sessionStorage.setItem('wellness.oauth-state', 'google:s')
    window.history.replaceState(null, '', '/?code=c&state=google:s')
    fetchMock.mockResolvedValue(new Response('', { status: 502 }))
    renderApp()

    expect((await screen.findByRole('alert')).textContent).toBe('요청에 실패했습니다. (502)')
    switchTo('EN')
    expect(screen.getByRole('alert').textContent).toBe('Request failed (502).')
  })
})
