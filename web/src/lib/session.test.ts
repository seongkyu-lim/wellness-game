import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TokenResponse } from '../api/authApi'
import { ApiError } from '../api/http'
import { tokenResponse } from '../test/fixtures'
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


/** 서버처럼 리프레시 토큰을 회전하고, 이미 회전된 토큰이 다시 오면 401(재사용)로 응답한다. */
function fakeServer(delayMs = 5) {
  let current = 1
  const refresh = vi.fn(async (refreshToken: string) => {
    await new Promise((resolve) => setTimeout(resolve, delayMs))
    if (refreshToken !== `ref-${current}`) {
      throw new ApiError(401, { raw: 'reused' })
    }
    current++
    return tokenResponse(current)
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
  tabs[0].start('kakao', tokenResponse(1, expiresIn))
  tabs.slice(1).forEach((tab) => tab.load())
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 30))

describe('저장 위치', () => {
  it('리프레시 토큰은 localStorage, 액세스 토큰은 메모리와 탭 저장소에만 둔다', () => {
    const tab = memoryStore()
    const manager = new SessionManager({ local: localStorage, tab, channel: null })
    manager.start('naver', tokenResponse(1))

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
    a.start('kakao', tokenResponse(1))
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

/** 다른 탭을 흉내 내기 위해 메시지를 직접 넣고, 이 탭이 보낸 메시지를 기록하는 채널. */
function controlledChannel() {
  const sent: Array<{ type: string; tabId: string }> = []
  const channel: ChannelLike & { deliver(data: unknown): void; sent: typeof sent } = {
    onmessage: null,
    sent,
    postMessage(message) {
      sent.push(message as { type: string; tabId: string })
    },
    deliver(data) {
      this.onmessage?.({ data })
    },
  }
  return channel
}

describe('저장값 검증', () => {
  it.each([
    ['JSON이 아님', '{not json'],
    ['객체가 아님', '"kakao"'],
    ['null', 'null'],
    ['지원하지 않는 provider', JSON.stringify({ provider: 'apple', userId: 'a', displayName: null, refreshToken: 'r', refreshExpiresAt: Date.now() + 1e6 })],
    ['빈 userId', JSON.stringify({ provider: 'kakao', userId: '', displayName: null, refreshToken: 'r', refreshExpiresAt: Date.now() + 1e6 })],
    ['displayName 형식 오류', JSON.stringify({ provider: 'kakao', userId: 'u', displayName: 3, refreshToken: 'r', refreshExpiresAt: Date.now() + 1e6 })],
    ['refreshExpiresAt 없음', JSON.stringify({ provider: 'kakao', userId: 'u', displayName: null, refreshToken: 'r' })],
  ])('%s인 세션은 지우고 탭의 액세스 토큰 사본도 지운다', (_, raw) => {
    const tab = memoryStore()
    tab.setItem(ACCESS_KEY, JSON.stringify({ userId: 'u', accessToken: 'a', expiresAt: Date.now() + 1e6 }))
    localStorage.setItem(SESSION_KEY, raw)
    expect(new SessionManager({ local: localStorage, tab, channel: null }).load()).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
    expect(tab.getItem(ACCESS_KEY)).toBeNull()
  })

  it('탭 사본이 다른 사용자 것이거나 손상됐으면 쓰지 않고 액세스 토큰 없이 시작한다', () => {
    new SessionManager({ local: localStorage, tab: memoryStore(), channel: null }).start('kakao', tokenResponse(1))
    for (const copy of [JSON.stringify({ userId: 'other', accessToken: 'x', expiresAt: Date.now() + 1e6 }), '{broken']) {
      const tab = memoryStore()
      tab.setItem(ACCESS_KEY, copy)
      expect(new SessionManager({ local: localStorage, tab, channel: null }).load()).toMatchObject({ accessToken: null, expiresAt: 0 })
    }
  })

  it('refreshExpiresIn이 없는 응답은 리프레시 토큰을 즉시 만료로 저장해 다음 로드에서 로그아웃된다', () => {
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null })
    manager.start('kakao', { ...tokenResponse(1), refreshExpiresIn: undefined })
    expect(new SessionManager({ local: localStorage, tab: memoryStore(), channel: null }).load()).toBeNull()
  })
})

describe('만료 처리', () => {
  it('이전 형식 세션의 액세스 토큰이 만료되면 요청 시 세션을 지우고 다른 탭에 만료로 알린다', async () => {
    let now = 1_000_000
    const channel = controlledChannel()
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel, now: () => now })
    manager.start('kakao', { ...tokenResponse(1, 60), refreshToken: undefined })

    await expect(manager.accessToken()).resolves.toBe('acc-1') // 60초 안이지만 갱신 수단이 없으니 그대로 쓴다.
    now += 61_000
    await expect(manager.accessToken()).rejects.toMatchObject({ status: 401, userMessage: { key: 'error.sessionExpired' } })
    expect(manager.current()).toBeNull()
    expect(channel.sent).toContainEqual({ type: 'signed-out', tabId: manager.tabId, reason: 'expired' })
  })

  it('미리 갱신이 네트워크 오류로 실패했고 액세스 토큰도 이미 만료됐으면 오류를 그대로 던진다(세션은 유지)', async () => {
    let now = 1_000_000
    const refresh = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null, refresh, now: () => now })
    manager.start('kakao', tokenResponse(1, 60))
    now += 61_000

    await expect(manager.accessToken()).rejects.toBeInstanceOf(TypeError)
    expect(manager.current()?.refreshToken).toBe('ref-1')
  })

  it('refresh가 400(잘못된 토큰)이어도 401처럼 세션을 끝낸다', async () => {
    const refresh = vi.fn().mockRejectedValue(new ApiError(400, { raw: 'bad' }))
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null, refresh })
    manager.start('kakao', tokenResponse(1))
    await expect(manager.refresh()).rejects.toMatchObject({ status: 401 })
    expect(manager.current()).toBeNull()
  })

  it('401을 받았는데 그 사이 다른 요청이 이미 갱신했으면 다시 갱신하지 않고 새 토큰을 준다', async () => {
    const refresh = vi.fn(async () => tokenResponse(2))
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null, refresh })
    manager.start('kakao', tokenResponse(1))
    await manager.refresh()

    await expect(manager.handleUnauthorized('acc-1')).resolves.toBe('acc-2')
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('갱신 도중 로그아웃하면 늦게 온 갱신 결과로 세션을 되살리지 않는다', async () => {
    let resolveRefresh!: (value: TokenResponse) => void
    const refresh = vi.fn(() => new Promise<TokenResponse>((resolve) => (resolveRefresh = resolve)))
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null, refresh, logout: vi.fn() })
    manager.start('kakao', tokenResponse(1))

    const pending = manager.refresh().catch((e: unknown) => e)
    await vi.waitFor(() => expect(refresh).toHaveBeenCalled())
    await manager.logout()
    resolveRefresh(tokenResponse(2))

    expect(await pending).toMatchObject({ status: 401 })
    expect(manager.current()).toBeNull()
    expect(localStorage.getItem(SESSION_KEY)).toBeNull()
  })

  it('갱신 도중 다시 로그인했으면 이전 세션의 갱신 거부가 새 세션을 지우지 않는다', async () => {
    let rejectRefresh!: (e: unknown) => void
    const refresh = vi.fn(() => new Promise<TokenResponse>((_, reject) => (rejectRefresh = reject)))
    const manager = new SessionManager({ local: localStorage, tab: memoryStore(), channel: null, refresh })
    manager.start('kakao', tokenResponse(1))

    const pending = manager.refresh().catch((e: unknown) => e)
    await vi.waitFor(() => expect(refresh).toHaveBeenCalled())
    manager.start('google', { ...tokenResponse(7), userId: 'google:7' })
    rejectRefresh(new ApiError(401, { raw: 'reused' }))

    expect(await pending).toMatchObject({ status: 401 })
    expect(manager.current()).toMatchObject({ provider: 'google', accessToken: 'acc-7' })
    expect(JSON.parse(localStorage.getItem(SESSION_KEY)!).refreshToken).toBe('ref-7')
  })
})

describe('탭 간 메시지 처리', () => {
  afterEach(() => vi.useRealTimers())

  function setup(options: { tabId?: string; remoteWaitMs?: number; claimWindowMs?: number } = {}) {
    const channel = controlledChannel()
    const refresh = vi.fn(async () => tokenResponse(2))
    const manager = new SessionManager({
      local: localStorage,
      tab: memoryStore(),
      channel,
      refresh,
      tabId: options.tabId ?? 'tab-m',
      remoteWaitMs: options.remoteWaitMs ?? 1_000,
      claimWindowMs: options.claimWindowMs ?? 20,
    })
    manager.start('kakao', tokenResponse(1))
    return { channel, refresh, manager }
  }

  it('자기 메시지·형식이 틀린 메시지·다른 사용자의 갱신 결과는 무시한다', () => {
    const { channel, manager } = setup()
    const onSignOut = vi.fn()
    manager.onRemoteSignOut(onSignOut)

    channel.deliver({ type: 'signed-out', tabId: manager.tabId, reason: 'logout' })
    channel.deliver(null)
    channel.deliver('signed-out')
    channel.deliver({ type: 'refreshed', tabId: 'tab-x', session: { ...manager.current()!, userId: 'other', accessToken: 'evil' } })

    expect(onSignOut).not.toHaveBeenCalled()
    expect(manager.current()?.accessToken).toBe('acc-1')
  })

  it('해제 함수를 부르면 더 이상 다른 탭 로그아웃 알림을 받지 않는다', () => {
    const { channel, manager } = setup()
    const onSignOut = vi.fn()
    const unsubscribe = manager.onRemoteSignOut(onSignOut)
    unsubscribe()
    channel.deliver({ type: 'signed-out', tabId: 'tab-x', reason: 'logout' })
    expect(onSignOut).not.toHaveBeenCalled()
    expect(manager.current()).toBeNull()
  })

  it('갱신 중이라던 탭이 기한 안에 결과를 알리지 않으면(닫힘) 기한 뒤에 직접 갱신한다', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup({ remoteWaitMs: 1_000 })
    channel.deliver({ type: 'refresh-start', tabId: 'tab-a' })

    const result = manager.refresh()
    await vi.advanceTimersByTimeAsync(999)
    expect(refresh).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1 + 20) // 기한 + 선점 대기
    await expect(result).resolves.toBe('acc-2')
    expect(refresh).toHaveBeenCalledWith('ref-1')
    expect(channel.sent.map((m) => m.type)).toEqual(['refresh-start', 'refreshed'])
  })

  it('동시에 선점하면 tabId가 작은 탭에 양보하고 그 결과를 받아 쓴다', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup({ tabId: 'tab-m' })
    const result = manager.refresh()
    await vi.advanceTimersByTimeAsync(0)
    expect(channel.sent).toEqual([{ type: 'refresh-start', tabId: 'tab-m' }])

    channel.deliver({ type: 'refresh-start', tabId: 'tab-a' }) // 'tab-a' < 'tab-m'
    await vi.advanceTimersByTimeAsync(20)
    expect(channel.sent.at(-1)).toEqual({ type: 'refresh-end', tabId: 'tab-m' })

    // 이긴 탭은 실제로 그러듯 회전된 리프레시 토큰을 공유 저장소에 먼저 쓰고 결과를 알린다.
    const rotated = { ...manager.current()!, accessToken: 'acc-2', expiresAt: Date.now() + 3_600_000, refreshToken: 'ref-2' }
    const stored = JSON.parse(localStorage.getItem(SESSION_KEY)!)
    localStorage.setItem(SESSION_KEY, JSON.stringify({ ...stored, refreshToken: 'ref-2' }))
    channel.deliver({ type: 'refreshed', tabId: 'tab-a', session: rotated })
    await expect(result).resolves.toBe('acc-2')
    expect(refresh).not.toHaveBeenCalled()
  })

  // 버그: 브라우저의 localStorage는 탭(프로세스) 사이에 비동기로 동기화되므로 'refreshed' 메시지가 저장소 반영보다
  // 먼저 도착할 수 있다. 이때 syncRefreshTokenFromStorage가 메모리의 새 토큰(ref-2)을 저장소의 옛 토큰(ref-1)으로
  // 되돌리고 다시 선점해 이미 폐기된 ref-1을 보낸다 → 서버가 재사용으로 판정해 family 전체가 로그아웃된다.
  // 고치면 이 테스트가 통과하므로 it.fails를 it으로 바꾼다.
  it.fails('다른 탭의 갱신 결과가 저장소 반영보다 먼저 와도 폐기된 리프레시 토큰을 다시 보내지 않는다', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup({ tabId: 'tab-m' })
    // 결과를 기다리지 않고 상태만 기록한다. 고친 뒤 결과가 영영 안 와도 타임아웃이 아니라 아래 단언에서 실패하게 한다.
    let outcome: { value: string } | { error: unknown } | null = null
    manager.refresh().then(
      (value) => (outcome = { value }),
      (error: unknown) => (outcome = { error }),
    )
    await vi.advanceTimersByTimeAsync(0)
    channel.deliver({ type: 'refresh-start', tabId: 'tab-a' })
    await vi.advanceTimersByTimeAsync(20)

    const rotated = { ...manager.current()!, accessToken: 'acc-2', expiresAt: Date.now() + 3_600_000, refreshToken: 'ref-2' }
    channel.deliver({ type: 'refreshed', tabId: 'tab-a', session: rotated }) // 저장소는 아직 ref-1
    await vi.advanceTimersByTimeAsync(1_000) // 선점 대기·다른 탭 대기 기한보다 넉넉히

    // 의도한 실패 지점: 현재 구현은 여기서 폐기된 ref-1을 보낸다.
    expect(refresh.mock.calls).toEqual([])
    expect(outcome).toEqual({ value: 'acc-2' })
  })

  it('tabId가 더 큰 탭의 선점 메시지에는 양보하지 않는다', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup({ tabId: 'tab-a' })
    const result = manager.refresh()
    await vi.advanceTimersByTimeAsync(0)
    channel.deliver({ type: 'refresh-start', tabId: 'tab-z' })
    await vi.advanceTimersByTimeAsync(20)
    await expect(result).resolves.toBe('acc-2')
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('선점에 계속 지고 결과도 오지 않으면 무한히 기다리지 않고 error.network로 끝낸다(세션은 유지)', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup({ tabId: 'tab-m', remoteWaitMs: 1_000 })
    // 이 탭이 선점을 알릴 때마다 더 작은 tabId의 탭도 선점을 알리고, 그 탭은 결과 없이 사라진다.
    const post = channel.postMessage.bind(channel)
    channel.postMessage = (message) => {
      post(message)
      if ((message as { type: string }).type === 'refresh-start') {
        channel.deliver({ type: 'refresh-start', tabId: 'tab-a' })
      }
    }

    const result = manager.refresh().catch((e: unknown) => e)
    await vi.runAllTimersAsync()

    expect(await result).toMatchObject({ status: 0, userMessage: { key: 'error.network' } })
    expect(refresh).not.toHaveBeenCalled()
    expect(channel.sent.filter((m) => m.type === 'refresh-start')).toHaveLength(3)
    expect(manager.current()?.refreshToken).toBe('ref-1')
  })

  it('다른 탭이 로그아웃하면 기다리던 갱신은 만료(401)로 끝난다', async () => {
    vi.useFakeTimers()
    const { channel, refresh, manager } = setup()
    channel.deliver({ type: 'refresh-start', tabId: 'tab-a' })
    const result = manager.refresh().catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(10)

    channel.deliver({ type: 'signed-out', tabId: 'tab-a', reason: 'logout' })
    await vi.advanceTimersByTimeAsync(0)

    expect(await result).toMatchObject({ status: 401 })
    expect(refresh).not.toHaveBeenCalled()
  })
})
