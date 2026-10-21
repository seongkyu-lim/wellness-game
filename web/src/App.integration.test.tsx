import { act, fireEvent, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setLanguage } from './i18n/core'
import Root from './Root'
import { createFakeBackend, json, storeV2Session, type FakeBackend } from './test/fakeBackend'
import { activityEntry } from './test/fixtures'
import { renderWithI18n } from './test/render'
import { KST_EARLY_MORNING, KST_NOON, useTimeZone } from './test/timezone'

/**
 * App 통합 테스트. 네트워크 경계(fetch)만 가짜 백엔드로 바꾸고 Root부터 실제 컴포넌트·세션 관리자·API 클라이언트를 그대로 쓴다.
 *
 * 타이머: 30초 자동 새로고침(setInterval)과 토큰 만료 판단(Date)만 가짜로 돌린다. setTimeout은 실제 타이머로 남겨
 * React 스케줄러와 세션 관리자의 탭 간 선점 대기(수십 ms)가 그대로 동작하게 한다.
 * Testing Library의 waitFor는 내부 폴링에 setInterval을 쓰므로 여기서는 실제 setTimeout으로 기다리는 eventually를 쓴다.
 */

const NOW = new Date('2026-10-21T03:00:00Z').getTime() // KST 12:00 — UTC와 로컬 날짜가 같다.
const POLL_MS = 30_000

let server: FakeBackend

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  vi.setSystemTime(NOW)
  window.history.replaceState(null, '', '/')
  server = createFakeBackend()
})

afterEach(() => {
  vi.useRealTimers()
  setLanguage('ko')
})

function renderAt(path: string) {
  window.history.replaceState(null, '', path)
  return renderWithI18n(<Root />)
}

/** 조건이 맞을 때까지 실제 시간으로 조금씩 기다린다(최대 2초). */
async function eventually(assertion: () => void, timeoutMs = 2_000): Promise<void> {
  const start = performance.now()
  for (;;) {
    try {
      assertion()
      return
    } catch (error) {
      if (performance.now() - start > timeoutMs) {
        throw error
      }
      await act(() => new Promise<void>((resolve) => setTimeout(resolve, 5)))
    }
  }
}

/** 대기 중인 마이크로태스크와 그로 인한 React 갱신을 흘려보낸다(시간 지연 없음). */
const flushMicrotasks = () => act(async () => {})

/** 자동 새로고침 한 주기를 흘려보낸다. */
async function tickPoll(times = 1) {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      vi.advanceTimersByTime(POLL_MS)
    })
  }
}

const dataRequests = () => server.requests.filter((r) => r.method === 'GET')
const alertText = () => screen.queryByRole('alert')?.textContent ?? null

/** 로그인 상태로 대시보드를 띄우고 첫 조회(캐릭터·활동)가 끝날 때까지 기다린다. */
async function renderSignedIn() {
  storeV2Session(NOW)
  const view = renderAt('/')
  await eventually(() => expect(screen.getByText('Lv.4 · 새싹 단계')).toBeTruthy())
  expect(dataRequests()).toHaveLength(2)
  return view
}

describe('소셜 로그인 콜백', () => {
  it('code·state로 돌아오면 토큰을 교환하고 주소를 정리한 뒤 대시보드를 그린다', async () => {
    sessionStorage.setItem('wellness.oauth-state', 'kakao:st-1')
    sessionStorage.setItem('wellness.oauth-verifier', 'v'.repeat(43))
    renderAt('/?code=auth-code&state=kakao:st-1')

    // 교환이 끝나기 전에는 랜딩(로그인 버튼)을 잠깐이라도 그리지 않는다.
    expect(screen.queryByRole('button', { name: 'Kakao로 로그인' })).toBeNull()

    await eventually(() => expect(screen.getByText('kakao:1')).toBeTruthy())

    await eventually(() => expect(dataRequests()).toHaveLength(2))
    const [exchange] = server.requests
    expect(exchange).toMatchObject({
      method: 'POST',
      path: '/api/auth/kakao',
      body: { code: 'auth-code', redirectUri: `${window.location.origin}/`, codeVerifier: 'v'.repeat(43) },
    })
    expect(exchange.headers).not.toHaveProperty('Authorization')
    for (const req of dataRequests()) {
      expect(req.headers.Authorization).toBe('Bearer acc-1')
    }
    expect(window.location.search).toBe('')
    expect(sessionStorage.getItem('wellness.oauth-state')).toBeNull()
    expect(screen.getByText('Lv.4 · 새싹 단계')).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeTruthy()
    expect(alertText()).toBeNull()
  })

  it('교환이 서버 메시지와 함께 거부되면 서버 원문 배너와 랜딩을 보이고 데이터를 조회하지 않는다', async () => {
    sessionStorage.setItem('wellness.oauth-state', 'kakao:st-1')
    server.routes['POST /api/auth/kakao'] = () => json(400, { message: '인가 코드가 만료되었습니다.' })
    renderAt('/?code=old&state=kakao:st-1')

    await eventually(() => expect(alertText()).toBe('인가 코드가 만료되었습니다.'))
    expect(screen.getByRole('button', { name: 'Kakao로 로그인' })).toBeTruthy()
    expect(dataRequests()).toHaveLength(0)
    expect(localStorage.getItem('wellness.session')).toBeNull()
  })
})

describe('자동 새로고침', () => {
  it('30초마다 캐릭터·활동을 다시 조회해 앱이 동기화한 기록을 반영한다', async () => {
    await renderSignedIn()
    expect(screen.getByText('아직 동기화된 기록이 없어요')).toBeTruthy()

    server.state.activities = [activityEntry({ steps: 5_000, gainedXp: 10 })]
    await tickPoll()
    await eventually(() => expect(screen.getByText('5,000걸음')).toBeTruthy())
    expect(dataRequests()).toHaveLength(4)

    await tickPoll()
    await eventually(() => expect(dataRequests()).toHaveLength(6))
  })

  it('날짜를 바꾸면 그 날짜로 즉시 다시 조회하고 이후 주기도 그 날짜를 쓴다', async () => {
    await renderSignedIn()
    fireEvent.change(screen.getByLabelText('조회 날짜'), { target: { value: '2026-10-01' } })
    await eventually(() => expect(server.requestsTo('/api/health-activities').at(-1)?.path).toContain('date=2026-10-01'))

    await tickPoll()
    await eventually(() => expect(server.requestsTo('/api/health-activities')).toHaveLength(3))
    expect(server.requestsTo('/api/health-activities').at(-1)?.path).toContain('date=2026-10-01')
  })

  it('일시적인 네트워크 오류 배너는 다음 주기에 성공하면 사라진다', async () => {
    await renderSignedIn()
    const original = server.routes['GET /api/characters/me']
    server.routes['GET /api/characters/me'] = () => {
      throw new TypeError('Failed to fetch')
    }
    await tickPoll()
    await eventually(() => expect(alertText()).toBe('서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.'))

    server.routes['GET /api/characters/me'] = original
    await tickPoll()
    await eventually(() => expect(alertText()).toBeNull())
    // 네트워크 오류는 로그아웃 사유가 아니다.
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeTruthy()
  })
})

describe('조회 날짜(로컬 오늘)', () => {
  useTimeZone('Asia/Seoul')

  async function firstActivitiesDate(now: Date) {
    vi.setSystemTime(now)
    storeV2Session(now.getTime())
    renderAt('/')
    await eventually(() => expect(server.requestsTo('/api/health-activities')).toHaveLength(1))
    return new URL(server.requestsTo('/api/health-activities')[0].path, 'http://x').searchParams.get('date')
  }

  it('한국 시간 낮에는 오늘 기록을 조회한다', async () => {
    expect(await firstActivitiesDate(KST_NOON)).toBe('2026-10-22')
  })

  // 버그: App이 오늘을 toISOString()(UTC)으로 구해 KST 00:00~09:00에는 전날 기록을 '오늘'로 조회한다.
  // 헤더의 날짜 표시(로컬)와 조회 날짜가 어긋난다. 고치면 it.fails를 it으로 바꾼다.
  it.fails('한국 시간 새벽에도 첫 조회 날짜는 로컬 오늘이어야 한다', async () => {
    expect(await firstActivitiesDate(KST_EARLY_MORNING)).toBe('2026-10-22')
  })
})

describe('토큰 갱신', () => {
  it('액세스 토큰 만료가 다가오면 다음 조회 전에 조용히 갱신하고 배너 없이 계속 보여 준다', async () => {
    await renderSignedIn()

    vi.setSystemTime(NOW + 3_590_000) // 만료 10초 전
    await tickPoll()
    await eventually(() => expect(dataRequests()).toHaveLength(4))

    expect(server.requestsTo('/api/auth/refresh')).toHaveLength(1)
    expect(server.requestsTo('/api/auth/refresh')[0].body).toEqual({ refreshToken: 'ref-1' })
    expect(dataRequests().slice(2).map((r) => r.headers.Authorization)).toEqual(['Bearer acc-2', 'Bearer acc-2'])
    expect(JSON.parse(localStorage.getItem('wellness.session')!).refreshToken).toBe('ref-2')
    expect(alertText()).toBeNull()
    expect(screen.getByText('Lv.4 · 새싹 단계')).toBeTruthy()
  })

  it('서버가 아직 유효해 보이는 토큰을 401로 거부하면 refresh 한 번으로 두 조회를 모두 재시도한다', async () => {
    await renderSignedIn()
    // 서버가 acc-1만 무효화한 상황(리프레시 토큰 ref-1은 아직 유효). 두 조회가 동시에 401을 받는다.
    server.revokeAccess()

    await tickPoll()
    await eventually(() => expect(dataRequests().filter((r) => r.headers.Authorization === 'Bearer acc-2')).toHaveLength(2))

    expect(server.requestsTo('/api/auth/refresh')).toHaveLength(1)
    expect(dataRequests().slice(2, 4).map((r) => r.headers.Authorization)).toEqual(['Bearer acc-1', 'Bearer acc-1'])
    expect(alertText()).toBeNull()
  })

  it('refresh가 거부되면 로그아웃 상태로 돌아가 만료 배너를 보이고 자동 새로고침을 멈춘다', async () => {
    await renderSignedIn()
    server.routes['POST /api/auth/refresh'] = () => json(401, { message: '만료' })

    vi.setSystemTime(NOW + 3_590_000)
    await tickPoll()
    await eventually(() => expect(alertText()).toBe('로그인이 만료되었습니다. 다시 로그인해 주세요.'))

    expect(screen.getByRole('button', { name: 'Kakao로 로그인' })).toBeTruthy()
    expect(screen.queryByText('kakao:1')).toBeNull()
    expect(localStorage.getItem('wellness.session')).toBeNull()
    expect(sessionStorage.getItem('wellness.access')).toBeNull()

    const before = server.requests.length
    await tickPoll(3)
    expect(server.requests).toHaveLength(before)
  })
})

describe('로그아웃', () => {
  it('서버에 리프레시 토큰 폐기를 요청하고 랜딩으로 돌아가며 이후 조회하지 않는다', async () => {
    await renderSignedIn()

    act(() => screen.getByRole('button', { name: '로그아웃' }).click())

    expect(screen.getByRole('button', { name: 'Kakao로 로그인' })).toBeTruthy()
    await eventually(() => expect(server.requestsTo('/api/auth/logout')).toHaveLength(1))
    const [logout] = server.requestsTo('/api/auth/logout')
    expect(logout.body).toEqual({ refreshToken: 'ref-1' })
    expect(logout.headers).not.toHaveProperty('Authorization')
    expect(localStorage.getItem('wellness.session')).toBeNull()
    expect(alertText()).toBeNull()

    const before = server.requests.length
    await tickPoll(2)
    expect(server.requests).toHaveLength(before)
  })

  it('로그아웃 전에 보낸 조회의 늦은 응답(성공·실패)은 로그아웃 화면을 덮어쓰지 않는다', async () => {
    await renderSignedIn()
    const held: Array<() => void> = []
    const released: Response[] = []
    const hold = (response: () => Response) => () =>
      new Promise<Response>((resolve) =>
        held.push(() => {
          const res = response()
          released.push(res)
          resolve(res)
        }),
      )
    server.routes['GET /api/characters/me'] = hold(() => json(200, { userId: 'kakao:1', character: { ...server.state.character, level: 20 } }))
    server.routes['GET /api/health-activities'] = hold(() => json(500, { message: '늦은 오류' }))

    await tickPoll()
    await eventually(() => expect(held).toHaveLength(2))
    act(() => screen.getByRole('button', { name: '로그아웃' }).click())
    await act(async () => held.forEach((release) => release()))
    // 앱이 두 응답 본문을 모두 읽어 처리를 마칠 때까지 기다린다.
    await eventually(() => expect(released.map((res) => res.bodyUsed)).toEqual([true, true]))
    await flushMicrotasks()

    expect(alertText()).toBeNull()
    // 대시보드 전용 요소(경험치 막대·활동 기록)가 되살아나지 않는다.
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(screen.queryByRole('region', { name: '활동 기록' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Kakao로 로그인' })).toBeTruthy()
  })

  it('서버 로그아웃이 실패해도 로컬 로그아웃은 끝나고 오류를 보이지 않는다', async () => {
    await renderSignedIn()
    server.routes['POST /api/auth/logout'] = () => {
      throw new TypeError('Failed to fetch')
    }

    act(() => screen.getByRole('button', { name: '로그아웃' }).click())
    await eventually(() => expect(server.requestsTo('/api/auth/logout')).toHaveLength(1))

    expect(screen.getByRole('button', { name: 'Kakao로 로그인' })).toBeTruthy()
    expect(localStorage.getItem('wellness.session')).toBeNull()
    expect(alertText()).toBeNull()
  })
})

describe('언어 전환', () => {
  it('대시보드 문구를 다시 그리고, 이후 요청은 새 Accept-Language로 보낸다', async () => {
    await renderSignedIn()
    act(() => screen.getByRole('button', { name: 'EN' }).click())

    expect(screen.getByRole('heading', { name: "Today's quests" })).toBeTruthy()
    expect(screen.getByText('Lv.4 · Sprout stage')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy()
    expect(document.documentElement.lang).toBe('en')
    // 언어 전환만으로는 다시 조회하지 않는다.
    expect(dataRequests()).toHaveLength(2)

    await tickPoll()
    await eventually(() => expect(dataRequests()).toHaveLength(4))
    expect(dataRequests().slice(2).map((r) => r.headers['Accept-Language'])).toEqual(['en', 'en'])
  })

  it('사전 키 오류 배너는 재번역하고, 서버 원문 배너는 다음 주기에 새 언어로 다시 받는다', async () => {
    await renderSignedIn()
    server.routes['GET /api/characters/me'] = () => json(500, {})
    await tickPoll()
    await eventually(() => expect(alertText()).toBe('요청에 실패했습니다. (500)'))

    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(alertText()).toBe('Request failed (500).')

    server.routes['GET /api/characters/me'] = (req) =>
      json(503, { message: req.headers['Accept-Language'] === 'en' ? 'Under maintenance' : '점검 중' })
    await tickPoll()
    await eventually(() => expect(alertText()).toBe('Under maintenance'))
  })
})

describe('법적 고지 라우트', () => {
  it.each([
    ['/privacy', '개인정보 처리방침'],
    ['/terms/', '이용약관'],
  ])('%s는 로그인 없이 문서를 그리고 홈 링크를 둔다', (path, title) => {
    renderAt(path)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(title)
    expect(screen.getByRole('link', { name: '홈으로' }).getAttribute('href')).toBe('/')
    expect(server.requests).toHaveLength(0)
  })

  it('법적 고지 주소에 code·state가 붙어 있어도 로그인 교환을 시도하지 않는다', async () => {
    sessionStorage.setItem('wellness.oauth-state', 'kakao:s')
    renderAt('/privacy?code=c&state=kakao:s')
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('개인정보 처리방침')
    await flushMicrotasks()
    expect(server.requests).toHaveLength(0)
    expect(sessionStorage.getItem('wellness.oauth-state')).toBe('kakao:s')
  })

  it('로그인 상태여도 법적 고지 페이지는 데이터를 조회하지 않는다', async () => {
    storeV2Session(NOW)
    renderAt('/terms')
    await tickPoll()
    expect(server.requests).toHaveLength(0)
  })
})

describe('읽기 전용 규칙(회귀 방지)', () => {
  // 웹은 조회 전용 대시보드다. 기록을 만들거나 바꾸는 요청은 인증 경로(/api/auth/*) 밖으로 나가면 안 된다.
  const isWrite = (method: string) => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
  const writesOutsideAuth = () => server.requests.filter((r) => isWrite(r.method) && !r.path.startsWith('/api/auth/'))

  /** 화면의 모든 버튼·입력·폼을 건드린다. 로그아웃처럼 화면을 바꾸는 버튼은 마지막에 누른다. */
  async function exerciseEveryControl(lastButtonName?: string) {
    for (const form of Array.from(document.querySelectorAll('form'))) {
      fireEvent.submit(form)
    }
    for (const input of Array.from(document.querySelectorAll('input, select, textarea'))) {
      fireEvent.change(input, { target: { value: '2026-10-02' } })
    }
    // 언어 버튼을 누르면 문구가 바뀌므로 마지막에 누를 버튼은 요소로 먼저 잡아 둔다.
    const last = lastButtonName ? screen.getByRole('button', { name: lastButtonName }) : null
    for (const button of screen.queryAllByRole('button').filter((b) => b !== last)) {
      act(() => button.click())
    }
    await tickPoll()
    if (last) {
      act(() => last.click())
    }
    await flushMicrotasks()
  }

  it('대시보드에는 폼이 없고 입력은 조회 날짜뿐이다', async () => {
    await renderSignedIn()
    expect(document.querySelectorAll('form')).toHaveLength(0)
    const inputs = Array.from(document.querySelectorAll('input, select, textarea'))
    expect(inputs.map((el) => el.getAttribute('type'))).toEqual(['date'])
  })

  it('대시보드의 모든 컨트롤을 눌러도 인증 경로 밖으로 쓰기 요청을 보내지 않는다', async () => {
    server.state.activities = [activityEntry({ steps: 1_000, gainedXp: 5 })]
    await renderSignedIn()
    await exerciseEveryControl('로그아웃')
    // 양성 신호: 바꾼 날짜로 다시 조회했고, 마지막에 누른 로그아웃 요청이 나갔다.
    await eventually(() => expect(server.requestsTo('/api/auth/logout')).toHaveLength(1))
    expect(server.requests.some((r) => r.path.includes('date=2026-10-02'))).toBe(true)

    expect(writesOutsideAuth()).toEqual([])
    expect(server.requests.filter((r) => isWrite(r.method)).map((r) => r.path)).toEqual(['/api/auth/logout'])
  })

  it('랜딩의 모든 컨트롤을 눌러도 쓰기 요청을 보내지 않는다', async () => {
    renderAt('/')
    expect(document.querySelectorAll('form')).toHaveLength(0)
    await exerciseEveryControl()
    // 양성 신호: 언어 버튼까지 눌려 화면이 영어로 바뀌었다(모든 클릭이 처리됨).
    expect(document.documentElement.lang).toBe('en')
    expect(server.requests.filter((r) => isWrite(r.method))).toEqual([])
  })

  it('소스 코드의 쓰기 요청은 모두 /api/auth/ 경로의 fetch이고 다른 전송 수단·폼은 없다', () => {
    const sources = import.meta.glob<string>(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}', '!./test/**'], {
      query: '?raw',
      import: 'default',
      eager: true,
    })
    expect(Object.keys(sources).length).toBeGreaterThan(10)
    const writeMethod = /method:\s*['"`](POST|PUT|PATCH|DELETE)['"`]/gi
    const otherTransport = /\bsendBeacon\b|\bXMLHttpRequest\b|\bnew\s+Request\s*\(|<form[\s>]/

    const problems: string[] = []
    let authWrites = 0
    for (const [file, code] of Object.entries(sources)) {
      if (otherTransport.test(code)) {
        problems.push(`${file}: fetch 밖의 전송 수단 또는 폼`)
      }
      // 각 fetch( 호출의 첫 인자(주소)와 바로 뒤 옵션 일부를 본다.
      let writesInFetch = 0
      for (const match of code.matchAll(/\bfetch\(\s*([^,)]+)/g)) {
        const target = match[1]
        const options = code.slice(match.index, match.index + 200)
        const isWrite = new RegExp(writeMethod.source, 'i').test(options)
        if (isWrite) {
          writesInFetch++
          if (!target.includes('/api/auth/')) {
            problems.push(`${file}: 인증 경로 밖 쓰기 요청 ${target}`)
          }
        }
        if (file === './api/authApi.ts' && !target.includes('/api/auth/')) {
          problems.push(`${file}: 인증 API 모듈의 fetch가 /api/auth/ 밖을 호출 ${target}`)
        }
      }
      // fetch 호출 밖에 놓인 쓰기 메서드(다른 래퍼로 넘기는 경우 등)도 잡는다.
      if ((code.match(writeMethod) ?? []).length !== writesInFetch) {
        problems.push(`${file}: fetch 호출 밖의 쓰기 메서드`)
      }
      if (file === './api/authApi.ts') {
        authWrites = writesInFetch
      }
    }
    expect(problems).toEqual([])
    // 로그인 교환·갱신·로그아웃 세 가지가 실제로 검사 대상이었다(정규식이 아무것도 못 찾는 상황 방지).
    expect(authWrites).toBe(3)
  })
})
