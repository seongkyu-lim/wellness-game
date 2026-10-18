import { describe, expect, it, vi } from 'vitest'
import type { TokenResponse } from '../api/authApi'
import { ApiError } from '../api/http'
import { ACCESS_KEY, SESSION_KEY, SessionManager, type ChannelLike, type KeyValueStore } from './session'

/** 같은 이름의 BroadcastChannel처럼, 보낸 인스턴스를 뺀 나머지에 비동기로 복사본을 전달한다. */
function createChannelHub() {
  const members = new Set<ChannelLike>()
  return () => {
    const channel: ChannelLike = {
      onmessage: null,
      postMessage(message) {
        for (const other of members) {
          if (other !== channel) {
            setTimeout(() => other.onmessage?.({ data: structuredClone(message) }), 0)
          }
        }
      },
    }
    members.add(channel)
    return channel
  }
}

/** 탭마다 따로인 sessionStorage 대용. */
function memoryStore(): KeyValueStore {
  const map = new Map<string, string>()
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  }
}

function tokens(n: number, expiresIn = 3600): TokenResponse {
  return {
    userId: 'kakao:1',
    displayName: null,
    accessToken: `acc-${n}`,
    tokenType: 'Bearer',
    expiresIn,
    refreshToken: `ref-${n}`,
    refreshExpiresIn: 2_592_000,
  }
}

/** 서버처럼 리프레시 토큰을 회전하고, 이미 회전된 토큰이 다시 오면 401(재사용)로 응답한다. */
function fakeServer(delayMs = 5) {
  let current = 1
  const refresh = vi.fn(async (refreshToken: string) => {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (refreshToken !== `ref-${current}`) {
      throw new ApiError(401, { raw: 'reused' })
    }
    current++
    return tokens(current)
  })
  return { refresh }
}

function createTabs(count: number, refresh: (token: string) => Promise<TokenResponse>) {
  const channel = createChannelHub()
  return Array.from(
    { length: count },
    (_, i) =>
      new SessionManager({
        local: localStorage,
        tab: memoryStore(),
        channel: channel(),
        refresh,
        tabId: `tab-${i}`,
        claimWindowMs: 20,
        remoteWaitMs: 2_000,
      }),
  )
}

/** 첫 탭에서 로그인하고, 나머지 탭은 저장소에서 세션을 읽는다(새 탭: 액세스 토큰 없음). */
function signInAll(tabs: SessionManager[], expiresIn = 3600) {
  tabs[0].start('kakao', tokens(1, expiresIn))
  tabs.slice(1).forEach((tab) => tab.load())
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 30))

describe('저장 위치', () => {
  it('리프레시 토큰은 localStorage, 액세스 토큰은 메모리와 탭 저장소에만 둔다', () => {
    const tab = memoryStore()
    const manager = new SessionManager({ local: localStorage, tab, channel: null })
    manager.start('naver', tokens(1))

    const stored = JSON.parse(localStorage.getItem(SESSION_KEY)!)
    expect(stored).toMatchObject({ provider: 'naver', userId: 'kakao:1', refreshToken: 'ref-1' })
    expect(stored).not.toHaveProperty('accessToken')
    expect(JSON.parse(tab.getItem(ACCESS_KEY)!)).toMatchObject({ userId: 'kakao:1', accessToken: 'acc-1' })

    // 새로고침: 같은 탭 저장소에서 액세스 토큰을 되살린다.
    expect(new SessionManager({ local: localStorage, tab, channel: null }).load()).toMatchObject({
      accessToken: 'acc-1',
      refreshToken: 'ref-1',
    })
    // 새 탭: 액세스 토큰 없이 시작해 첫 요청 전에 갱신한다.
    expect(new SessionManager({ local: localStorage, tab: memoryStore(), channel: null }).load()).toMatchObject({
      accessToken: null,
      expiresAt: 0,
      refreshToken: 'ref-1',
    })
  })

  it('리프레시 토큰이 만료된 세션은 지운다', () => {
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null })
    localStorage.setItem(
      SESSION_KEY,
      JSON.stringify({ provider: 'kakao', userId: 'kakao:1', displayName: null, refreshToken: 'r', refreshExpiresAt: Date.now() - 1 }),
    )
    expect(manager.load()).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })
})

describe('탭 간 갱신 조율', () => {
  it('여러 탭이 동시에 갱신해도 서버 refresh는 한 번이고 모두 같은 새 토큰을 쓴다', async () => {
    const server = fakeServer()
    const tabs = createTabs(3, server.refresh)
    signInAll(tabs, 30) // 만료 임박

    const results = await Promise.all(tabs.map((tab) => tab.accessToken()))

    expect(server.refresh).toHaveBeenCalledTimes(1)
    expect(results).toEqual(['acc-2', 'acc-2', 'acc-2'])
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!).refreshToken).toBe('ref-2')
    tabs.forEach((tab) => expect(tab.current()?.refreshToken).toBe('ref-2'))
  })

  it('다른 탭이 갱신 중이면 결과를 기다려 반영하고 직접 refresh하지 않는다', async () => {
    const server = fakeServer(50)
    const [a, b] = createTabs(2, server.refresh)
    signInAll([a, b], 30)

    const first = a.refresh()
    await flush() // a의 refresh-start가 b에 도착하고 a는 서버 요청 중
    const second = b.accessToken()

    await expect(first).resolves.toBe('acc-2')
    await expect(second).resolves.toBe('acc-2')
    expect(server.refresh).toHaveBeenCalledTimes(1)
  })

  it('다른 탭이 회전한 토큰을 메시지로 받아 반영하므로 이후 요청에서 갱신하지 않는다', async () => {
    const server = fakeServer()
    const [a, b] = createTabs(2, server.refresh)
    signInAll([a, b])

    await a.refresh()
    await flush()

    expect(b.current()).toMatchObject({ accessToken: 'acc-2', refreshToken: 'ref-2' })
    await expect(b.accessToken()).resolves.toBe('acc-2')
    expect(server.refresh).toHaveBeenCalledTimes(1)
  })

  it('메시지를 못 받았어도 localStorage의 회전된 리프레시 토큰으로 갱신해 재사용 판정을 피한다', async () => {
    const server = fakeServer()
    const local = memoryStore()
    const a = new SessionManager({ local, tab: memoryStore(), channel: null, refresh: server.refresh })
    const b = new SessionManager({ local, tab: memoryStore(), channel: null, refresh: server.refresh })
    a.start('kakao', tokens(1))
    b.load()

    await a.refresh() // ref-1 → ref-2 (b는 모름)
    await expect(b.refresh()).resolves.toBe('acc-3')

    expect(server.refresh.mock.calls.map(([token]) => token)).toEqual(['ref-1', 'ref-2'])
  })

  it('한 탭에서 로그아웃하거나 만료되면 다른 탭도 세션을 지우고 이유를 알린다', async () => {
    const [a, b, c] = createTabs(3, fakeServer().refresh)
    signInAll([a, b, c])
    const onB = vi.fn()
    const onC = vi.fn()
    b.onRemoteSignOut(onB)
    c.onRemoteSignOut(onC)

    await a.logout()
    await flush()

    expect(onB).toHaveBeenCalledWith('logout')
    expect(onC).toHaveBeenCalledWith('logout')
    expect(b.current()).toBeNull()
    await expect(b.accessToken()).rejects.toMatchObject({ status: 401 })
  })

  it('refresh가 거부되면 갱신하던 탭이 세션을 지우고 기다리던 탭은 만료로 끝난다', async () => {
    const refresh = vi.fn(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30))
      throw new ApiError(401, { raw: 'expired' })
    })
    const [a, b] = createTabs(2, refresh)
    signInAll([a, b], 30)
    const onB = vi.fn()
    b.onRemoteSignOut(onB)

    const first = a.refresh().catch((e: unknown) => e)
    await flush()
    const second = b.accessToken().catch((e: unknown) => e)

    expect(await first).toMatchObject({ status: 401 })
    expect(await second).toMatchObject({ status: 401 })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(onB).toHaveBeenCalledWith('expired')
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })
})
