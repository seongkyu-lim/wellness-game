import { postLogout, postRefresh, type TokenResponse } from '../api/authApi'
import { ApiError, isUnauthorized, sessionExpiredError } from '../api/http'

// iOS 앱과 동일한 provider 집합 (Apple은 유료 개발자 계정 + HTTPS 필요로 웹 미지원)
export type Provider = 'google' | 'kakao' | 'naver'

const PROVIDERS: readonly Provider[] = ['google', 'kakao', 'naver']

export function isProvider(value: unknown): value is Provider {
  return PROVIDERS.includes(value as Provider)
}

export interface Session {
  provider: Provider
  userId: string
  displayName: string | null
  /** 서버 발급 Bearer 액세스 토큰. 새 탭처럼 아직 메모리에 없으면 null이고 첫 요청 전에 갱신한다. */
  accessToken: string | null
  /** 액세스 토큰 만료 시각 (epoch ms). 토큰이 없으면 0 */
  expiresAt: number
  /** Auth v2 리프레시 토큰. 없으면 액세스 토큰 만료까지만 쓰는 이전 형식 세션이다. */
  refreshToken?: string
  /** 리프레시 토큰 만료 시각 (epoch ms) */
  refreshExpiresAt?: number
}

/** 다른 탭이 세션을 끝낸 이유. 'expired'면 로그인 만료 배너를 띄운다. */
export type SignOutReason = 'logout' | 'expired'

export type KeyValueStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

/** BroadcastChannel 중 실제로 쓰는 부분. 테스트는 가짜 구현을 넣는다. */
export interface ChannelLike {
  postMessage(message: unknown): void
  onmessage: ((event: { data: unknown }) => void) | null
}

type ChannelMessage =
  | { type: 'refresh-start'; tabId: string }
  | { type: 'refresh-end'; tabId: string }
  | { type: 'refreshed'; tabId: string; session: Session }
  | { type: 'signed-out'; tabId: string; reason: SignOutReason }

export const SESSION_KEY = 'wellness.session'
export const ACCESS_KEY = 'wellness.access'
// 제거된 게스트 기능이 남긴 키. 더 이상 사용하지 않으므로 발견하면 지운다.
const LEGACY_GUEST_ID_KEY = 'wellness.userId'
const CHANNEL_NAME = 'wellness.auth'

/** 액세스 토큰이 이 시간 안에 만료되면 요청 전에 미리 갱신한다. */
export const REFRESH_SKEW_MS = 60_000
/** 동시에 갱신을 시작한 탭끼리 선점 메시지를 주고받는 시간. tabId가 작은 탭이 이긴다. */
const DEFAULT_CLAIM_WINDOW_MS = 50
/** 다른 탭의 갱신 결과를 기다리는 최대 시간. 넘으면 그 탭이 닫혔다고 보고 직접 갱신한다. */
const DEFAULT_REMOTE_WAIT_MS = 10_000
/** 선점에 계속 지면 무한히 기다리지 않도록 시도 횟수를 제한한다. */
const MAX_REFRESH_ATTEMPTS = 3

export interface SessionManagerOptions {
  local: KeyValueStore
  /** 액세스 토큰 사본(새로고침 대비). 탭마다 따로 있다. */
  tab: KeyValueStore
  channel: ChannelLike | null
  refresh?: (refreshToken: string) => Promise<TokenResponse>
  logout?: (accessToken: string, refreshToken: string) => Promise<void>
  now?: () => number
  tabId?: string
  claimWindowMs?: number
  remoteWaitMs?: number
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/**
 * 세션 저장과 토큰 갱신을 맡는다.
 *
 * - 저장: 프로필과 리프레시 토큰은 localStorage(탭 간 공유·재방문 유지), 액세스 토큰은 메모리가 원본이고
 *   새로고침 대비 사본만 sessionStorage에 둔다. 리프레시 토큰이 없는 이전 형식 세션은 예전처럼 액세스 토큰을 localStorage에 둔다.
 * - 갱신: 탭 안에서는 진행 중 Promise를 공유해 한 번만, 탭 사이에서는 BroadcastChannel로 선점·결과를 주고받아
 *   같은 리프레시 토큰을 두 탭이 동시에 보내(재사용 판정 → family 폐기) 로그아웃되는 일을 막는다.
 */
export class SessionManager {
  readonly tabId: string
  private session: Session | null = null
  /** 로그인·로그아웃마다 증가. 끝난 세션의 갱신 결과가 새 상태를 덮어쓰지 않게 한다. */
  private generation = 0
  private inflight: Promise<string> | null = null
  private claiming = false
  private lostClaim = false
  /** 갱신 중이라고 알려온 다른 탭과 기다릴 기한. */
  private remoteRefreshers = new Map<string, number>()
  private remoteWaiters: Array<() => void> = []
  private signOutListeners = new Set<(reason: SignOutReason) => void>()
  private readonly opts: Required<Omit<SessionManagerOptions, 'channel' | 'tabId'>> & { channel: ChannelLike | null }

  constructor(options: SessionManagerOptions) {
    this.opts = {
      refresh: postRefresh,
      logout: postLogout,
      now: () => Date.now(),
      claimWindowMs: DEFAULT_CLAIM_WINDOW_MS,
      remoteWaitMs: DEFAULT_REMOTE_WAIT_MS,
      ...options,
    }
    this.tabId = options.tabId ?? crypto.randomUUID()
    if (this.opts.channel) {
      this.opts.channel.onmessage = (event) => this.onChannelMessage(event.data as ChannelMessage)
    }
  }

  private now(): number {
    return this.opts.now()
  }

  /** 메모리 상태(없으면 null). */
  current(): Session | null {
    return this.session
  }

  /**
   * 저장된 세션을 읽어 메모리에 올린다. 토큰이 없는 예전 형식이거나 만료됐거나 손상된 세션은
   * 지우고 로그아웃 상태(null)로 처리한다.
   */
  load(): Session | null {
    const { local } = this.opts
    local.removeItem(LEGACY_GUEST_ID_KEY)
    this.session = this.readStored()
    if (!this.session) {
      local.removeItem(SESSION_KEY)
      this.opts.tab.removeItem(ACCESS_KEY)
    }
    return this.session
  }

  private readStored(): Session | null {
    const raw = this.opts.local.getItem(SESSION_KEY)
    if (!raw) {
      return null
    }
    let v: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(raw)
      if (typeof parsed !== 'object' || parsed === null) {
        return null
      }
      v = parsed as Record<string, unknown>
    } catch {
      return null
    }
    if (
      !isProvider(v.provider) ||
      typeof v.userId !== 'string' ||
      !v.userId ||
      !(v.displayName === null || typeof v.displayName === 'string')
    ) {
      return null
    }
    const profile = { provider: v.provider, userId: v.userId, displayName: v.displayName }
    const now = this.now()

    if (typeof v.refreshToken === 'string' && v.refreshToken) {
      if (typeof v.refreshExpiresAt !== 'number' || v.refreshExpiresAt <= now) {
        return null
      }
      const access = this.readTabAccess(v.userId)
      return {
        ...profile,
        accessToken: access?.accessToken ?? null,
        expiresAt: access?.expiresAt ?? 0,
        refreshToken: v.refreshToken,
        refreshExpiresAt: v.refreshExpiresAt,
      }
    }
    // 리프레시 토큰이 없는 이전 형식: 액세스 토큰이 유효한 동안만 쓴다.
    if (typeof v.accessToken === 'string' && v.accessToken && typeof v.expiresAt === 'number' && v.expiresAt > now) {
      return { ...profile, accessToken: v.accessToken, expiresAt: v.expiresAt }
    }
    return null
  }

  private readTabAccess(userId: string): { accessToken: string; expiresAt: number } | null {
    try {
      const v = JSON.parse(this.opts.tab.getItem(ACCESS_KEY) ?? 'null') as Record<string, unknown> | null
      if (
        v &&
        v.userId === userId &&
        typeof v.accessToken === 'string' &&
        typeof v.expiresAt === 'number' &&
        v.expiresAt > this.now()
      ) {
        return { accessToken: v.accessToken, expiresAt: v.expiresAt }
      }
    } catch {
      // 손상된 사본은 무시하고 갱신으로 다시 받는다.
    }
    return null
  }

  private persist(session: Session): void {
    const { provider, userId, displayName } = session
    if (session.refreshToken) {
      const stored = { provider, userId, displayName, refreshToken: session.refreshToken, refreshExpiresAt: session.refreshExpiresAt }
      this.opts.local.setItem(SESSION_KEY, JSON.stringify(stored))
      this.writeTabAccess(session)
    } else {
      const stored = { provider, userId, displayName, accessToken: session.accessToken, expiresAt: session.expiresAt }
      this.opts.local.setItem(SESSION_KEY, JSON.stringify(stored))
    }
  }

  private writeTabAccess(session: Session): void {
    if (session.accessToken) {
      const { userId, accessToken, expiresAt } = session
      this.opts.tab.setItem(ACCESS_KEY, JSON.stringify({ userId, accessToken, expiresAt }))
    } else {
      this.opts.tab.removeItem(ACCESS_KEY)
    }
  }

  /** 로그인 응답으로 새 세션을 만들어 저장한다. */
  start(provider: Provider, tokens: TokenResponse): Session {
    const session: Session = {
      provider,
      userId: tokens.userId,
      displayName: tokens.displayName ?? null,
      ...this.tokenFields(tokens),
    }
    this.generation++
    this.session = session
    this.persist(session)
    return session
  }

  private tokenFields(tokens: TokenResponse): Pick<Session, 'accessToken' | 'expiresAt' | 'refreshToken' | 'refreshExpiresAt'> {
    const now = this.now()
    const fields: Pick<Session, 'accessToken' | 'expiresAt' | 'refreshToken' | 'refreshExpiresAt'> = {
      accessToken: tokens.accessToken,
      expiresAt: now + Math.max(0, tokens.expiresIn) * 1000,
    }
    if (tokens.refreshToken) {
      fields.refreshToken = tokens.refreshToken
      fields.refreshExpiresAt = now + Math.max(0, tokens.refreshExpiresIn ?? 0) * 1000
    }
    return fields
  }

  /**
   * 로컬 세션을 지우고 다른 탭에도 알린다. 이미 지워졌으면 아무것도 하지 않는다.
   * 지운 세션을 돌려준다(서버 로그아웃에 쓰기 위해).
   */
  clear(reason: SignOutReason): Session | null {
    const ended = this.session
    this.generation++
    this.session = null
    this.opts.local.removeItem(SESSION_KEY)
    this.opts.tab.removeItem(ACCESS_KEY)
    if (ended) {
      this.post({ type: 'signed-out', tabId: this.tabId, reason })
    }
    return ended
  }

  /**
   * 사용자 로그아웃. 로컬 세션은 즉시 지우고, 서버에 리프레시 토큰 family 폐기를 요청한다.
   * 서버 요청이 실패해도(네트워크·만료) 로컬 로그아웃은 이미 끝났으므로 오류를 삼킨다.
   */
  async logout(): Promise<void> {
    const ended = this.clear('logout')
    if (!ended?.refreshToken) {
      return
    }
    try {
      let accessToken = ended.accessToken && ended.expiresAt > this.now() ? ended.accessToken : null
      let refreshToken = ended.refreshToken
      if (!accessToken) {
        // logout은 Bearer가 필요하다. 액세스 토큰이 없으면 한 번 회전해 받은 토큰으로 폐기한다.
        const rotated = await this.opts.refresh(refreshToken)
        accessToken = rotated.accessToken
        refreshToken = rotated.refreshToken ?? refreshToken
      }
      await this.opts.logout(accessToken, refreshToken)
    } catch {
      // 로컬 로그아웃은 완료됐다.
    }
  }

  /** 다른 탭에서 로그아웃·세션 만료가 일어났을 때 호출된다. 해제 함수를 돌려준다. */
  onRemoteSignOut(listener: (reason: SignOutReason) => void): () => void {
    this.signOutListeners.add(listener)
    return () => this.signOutListeners.delete(listener)
  }

  /**
   * 요청에 쓸 액세스 토큰. 없거나 60초 안에 만료되면 먼저 갱신한다.
   * 세션을 이어갈 수 없으면 ApiError(401)를 던진다.
   */
  async accessToken(): Promise<string> {
    const s = this.session
    if (!s) {
      throw sessionExpiredError()
    }
    const now = this.now()
    if (s.accessToken && s.expiresAt - now > REFRESH_SKEW_MS) {
      return s.accessToken
    }
    if (s.refreshToken) {
      try {
        return await this.refresh()
      } catch (e) {
        // 네트워크 오류 등으로 미리 갱신하지 못했지만 아직 유효하면 그대로 쓴다.
        if (!isUnauthorized(e) && s.accessToken && s.expiresAt > this.now()) {
          return s.accessToken
        }
        throw e
      }
    }
    if (s.accessToken && s.expiresAt > now) {
      return s.accessToken
    }
    this.clear('expired')
    throw sessionExpiredError()
  }

  /**
   * 요청이 401을 받았을 때 호출한다. 재시도할 새 액세스 토큰을 돌려준다.
   * 리프레시 토큰이 없는 세션이면 세션을 지우고 null(받은 401을 그대로 쓴다), 갱신이 거부되면 ApiError(401)를 던진다.
   */
  async handleUnauthorized(usedToken: string): Promise<string | null> {
    const s = this.session
    if (!s) {
      return null
    }
    // 그 사이 다른 요청(또는 다른 탭)이 이미 갱신했다.
    if (s.accessToken && s.accessToken !== usedToken && s.expiresAt > this.now()) {
      return s.accessToken
    }
    if (!s.refreshToken) {
      this.clear('expired')
      return null
    }
    return this.refresh()
  }

  /** 리프레시 토큰을 회전해 새 액세스 토큰을 받는다. 탭 안에서 동시에 하나만 실행한다. */
  refresh(): Promise<string> {
    if (!this.inflight) {
      this.inflight = this.refreshAcrossTabs().finally(() => {
        this.inflight = null
      })
    }
    return this.inflight
  }

  private async refreshAcrossTabs(): Promise<string> {
    const generation = this.generation
    const startToken = this.session?.refreshToken
    for (let attempt = 0; attempt < MAX_REFRESH_ATTEMPTS; attempt++) {
      await this.waitForRemoteRefresh()
      const adopted = this.adoptOtherTabResult(generation, startToken)
      if (adopted) {
        return adopted
      }
      if (await this.claim()) {
        return this.refreshNow(generation)
      }
    }
    // 계속 선점에 졌다면 마지막으로 다른 탭 결과를 기다려 본다.
    await this.waitForRemoteRefresh()
    const adopted = this.adoptOtherTabResult(generation, startToken)
    if (adopted) {
      return adopted
    }
    throw new ApiError(0, { key: 'error.network' })
  }

  /** 다른 탭이 이미 회전해 메모리에 반영된 새 액세스 토큰이 있으면 그것을 쓴다. 세션이 끝났으면 401. */
  private adoptOtherTabResult(generation: number, startToken: string | undefined): string | null {
    if (generation !== this.generation || !this.session?.refreshToken) {
      throw sessionExpiredError()
    }
    this.syncRefreshTokenFromStorage()
    const s = this.session
    if (s.refreshToken !== startToken && s.accessToken && s.expiresAt - this.now() > REFRESH_SKEW_MS) {
      return s.accessToken
    }
    return null
  }

  /** 다른 탭이 회전한 리프레시 토큰을 localStorage에서 읽어 반영한다(이미 폐기된 토큰을 보내지 않도록). */
  private syncRefreshTokenFromStorage(): void {
    const s = this.session
    if (!s) {
      return
    }
    try {
      const stored = JSON.parse(this.opts.local.getItem(SESSION_KEY) ?? 'null') as Partial<Session> | null
      if (
        stored &&
        stored.userId === s.userId &&
        typeof stored.refreshToken === 'string' &&
        stored.refreshToken !== s.refreshToken
      ) {
        this.session = { ...s, refreshToken: stored.refreshToken, refreshExpiresAt: stored.refreshExpiresAt }
      }
    } catch {
      // 손상된 값이면 메모리 값을 그대로 쓴다.
    }
  }

  private async claim(): Promise<boolean> {
    this.claiming = true
    this.lostClaim = false
    this.post({ type: 'refresh-start', tabId: this.tabId })
    if (this.opts.channel) {
      await sleep(this.opts.claimWindowMs)
    }
    this.claiming = false
    if (this.lostClaim) {
      // 이긴 탭이 갱신하는 동안 다른 탭이 나를 기다리지 않게 물러났다고 알린다.
      this.post({ type: 'refresh-end', tabId: this.tabId })
    }
    return !this.lostClaim
  }

  private async refreshNow(generation: number): Promise<string> {
    this.syncRefreshTokenFromStorage()
    const s = this.session!
    let tokens: TokenResponse
    try {
      tokens = await this.opts.refresh(s.refreshToken!)
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 400)) {
        if (generation === this.generation) {
          this.clear('expired')
        } else {
          this.post({ type: 'refresh-end', tabId: this.tabId })
        }
        throw sessionExpiredError()
      }
      this.post({ type: 'refresh-end', tabId: this.tabId })
      throw e
    }
    if (generation !== this.generation || !this.session) {
      // 갱신 도중 로그아웃됐다. 새 세션을 되살리지 않는다.
      this.post({ type: 'refresh-end', tabId: this.tabId })
      throw sessionExpiredError()
    }
    const next: Session = { ...this.session, ...this.tokenFields(tokens) }
    this.session = next
    this.persist(next)
    this.post({ type: 'refreshed', tabId: this.tabId, session: next })
    return next.accessToken!
  }

  /** 갱신 중인 다른 탭이 결과를 알리거나 기한이 지날 때까지 기다린다. */
  private async waitForRemoteRefresh(): Promise<void> {
    for (;;) {
      const now = this.now()
      for (const [tabId, deadline] of this.remoteRefreshers) {
        if (deadline <= now) {
          // 기한 안에 결과가 없으면 그 탭은 닫힌 것으로 본다.
          this.remoteRefreshers.delete(tabId)
        }
      }
      if (this.remoteRefreshers.size === 0) {
        return
      }
      const remaining = Math.max(...this.remoteRefreshers.values()) - now
      await new Promise<void>((resolve) => {
        const timer = setTimeout(done, remaining)
        function done() {
          clearTimeout(timer)
          resolve()
        }
        this.remoteWaiters.push(done)
      })
    }
  }

  private settleRemoteRefresh(tabId: string): void {
    this.remoteRefreshers.delete(tabId)
    const waiters = this.remoteWaiters
    this.remoteWaiters = []
    waiters.forEach((resolve) => resolve())
  }

  private post(message: ChannelMessage): void {
    this.opts.channel?.postMessage(message)
  }

  private onChannelMessage(message: ChannelMessage): void {
    if (!message || typeof message !== 'object' || message.tabId === this.tabId) {
      return
    }
    switch (message.type) {
      case 'refresh-start':
        if (this.claiming && message.tabId < this.tabId) {
          this.lostClaim = true
        }
        this.remoteRefreshers.set(message.tabId, this.now() + this.opts.remoteWaitMs)
        return
      case 'refreshed': {
        const s = this.session
        if (s && s.userId === message.session.userId) {
          // 다른 탭이 회전한 토큰을 반영한다. 프로필(provider·이름)은 이 탭 값을 유지한다.
          const { accessToken, expiresAt, refreshToken, refreshExpiresAt } = message.session
          this.session = { ...s, accessToken, expiresAt, refreshToken, refreshExpiresAt }
          this.writeTabAccess(this.session)
        }
        this.settleRemoteRefresh(message.tabId)
        return
      }
      case 'refresh-end':
        this.settleRemoteRefresh(message.tabId)
        return
      case 'signed-out':
        this.settleRemoteRefresh(message.tabId)
        if (this.session) {
          this.generation++
          this.session = null
          this.opts.tab.removeItem(ACCESS_KEY)
          this.signOutListeners.forEach((listener) => listener(message.reason))
        }
        return
    }
  }
}

function createDefaultChannel(): ChannelLike | null {
  if (typeof BroadcastChannel === 'undefined') {
    return null
  }
  const channel = new BroadcastChannel(CHANNEL_NAME)
  // Node(테스트)에서는 열린 채널이 프로세스 종료를 막지 않게 한다.
  ;(channel as unknown as { unref?: () => void }).unref?.()
  return channel as unknown as ChannelLike
}

/** 앱 전역 세션 관리자. */
export const sessionManager = new SessionManager({
  local: localStorage,
  tab: sessionStorage,
  channel: createDefaultChannel(),
})
