import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchDailyActivities, fetchMyCharacter, isUnauthorized } from './api/client'
import type { ActivityEntry, Character } from './api/types'
import { AccountSection } from './components/AccountSection'
import { CharacterCard } from './components/CharacterCard'
import { ErrorBanner } from './components/ErrorBanner'
import { Landing } from './components/Landing'
import { TodayActivities } from './components/TodayActivities'
import { toMessage, type Message } from './i18n/core'
import { useI18n } from './i18n/I18nProvider'
import { LanguageToggle } from './components/LanguageToggle'
import {
  clearSession,
  completeLoginFromRedirect,
  loadSession,
  logout,
  onRemoteSignOut,
  type Session,
} from './lib/auth'

const REFRESH_INTERVAL_MS = 30_000

export default function App() {
  const { formatDate } = useI18n()
  const [session, setSession] = useState<Session | null>(loadSession)
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [character, setCharacter] = useState<Character | null>(null)
  const [activities, setActivities] = useState<ActivityEntry[]>([])
  const [loading, setLoading] = useState(false)
  // 번역된 문자열이 아니라 Message로 보관해, 언어를 바꾸면 떠 있는 배너도 다시 번역되게 한다.
  // 한계: 서버 원문({ raw }, 예: 로그인 교환 실패)은 재번역할 수 없고, 로그아웃 상태에서는 자동 새로고침도 없어 이전 언어로 남는다.
  const [error, setError] = useState<Message | null>(null)
  // 소셜 로그인에서 돌아온 직후(?code=)에는 토큰 교환이 끝날 때까지 랜딩을 그리지 않는다.
  const [completingLogin, setCompletingLogin] = useState(() => new URLSearchParams(window.location.search).has('code'))

  // 로그아웃·세션 교체 후 늦게 도착한 응답이 화면을 덮어쓰지 않도록 현재 세션을 추적한다.
  // 토큰 회전은 세션 관리자 안에서 일어나므로 이 객체는 로그인·로그아웃 때만 바뀐다.
  const activeSessionRef = useRef<Session | null>(session)
  activeSessionRef.current = session

  // 소셜 로그인 리다이렉트로 돌아온 경우 code를 서버 발급 토큰으로 교환한다.
  useEffect(() => {
    completeLoginFromRedirect()
      .then((newSession) => {
        if (newSession) {
          setError(null)
          setSession(newSession)
        }
      })
      .catch((e) => setError(toMessage(e, 'error.loginFailed')))
      .finally(() => setCompletingLogin(false))
  }, [])

  const resetToSignedOut = useCallback((message: Message | null) => {
    setSession(null)
    setCharacter(null)
    setActivities([])
    setError(message)
  }, [])

  // 다른 탭에서 로그아웃하거나 세션이 만료되면 이 탭도 따라간다.
  useEffect(
    () => onRemoteSignOut((reason) => resetToSignedOut(reason === 'expired' ? { key: 'error.sessionExpired' } : null)),
    [resetToSignedOut],
  )

  const expireSession = useCallback(() => {
    clearSession()
    resetToSignedOut({ key: 'error.sessionExpired' })
  }, [resetToSignedOut])

  const signOut = useCallback(() => {
    // 서버 폐기 요청은 기다리지 않는다. 로컬 세션은 logout() 안에서 즉시 지워진다.
    void logout()
    resetToSignedOut(null)
  }, [resetToSignedOut])

  // refresh는 언어(t)를 쓰지도, 의존성에 넣지도 않는다. 언어가 바뀌어도 재조회·30초 타이머 재시작을 하지 않기 위해서다.
  // 근거: 조회 응답(캐릭터 수치·활동 기록)에는 서버가 번역한 문구가 없고 화면 문구는 모두 클라이언트 사전으로 그린다.
  // 서버가 번역하는 것은 오류 message(`{ raw }`)뿐인데, 이는 떠 있는 동안만 이전 언어로 남고 다음 자동 새로고침
  // (최대 30초, 성공 시 배너 제거 또는 새 Accept-Language로 다시 받음)에서 자연히 갱신되므로 즉시 재조회할 가치가 없다.
  const refresh = useCallback(async () => {
    if (!session) {
      return
    }
    const { userId } = session
    const isStale = () => activeSessionRef.current !== session
    setLoading(true)
    setError(null)
    try {
      const [summary, daily] = await Promise.all([fetchMyCharacter(), fetchDailyActivities(userId, date)])
      if (isStale()) {
        return
      }
      setCharacter(summary?.character ?? null)
      setActivities(daily.activities)
    } catch (e) {
      if (isStale()) {
        return
      }
      if (isUnauthorized(e)) {
        expireSession()
        return
      }
      setError(toMessage(e, 'error.network'))
    } finally {
      if (!isStale()) {
        setLoading(false)
      }
    }
  }, [session, date, expireSession])

  // 앱이 동기화하면 자동으로 반영되도록 주기적으로 새로고침한다. 로그인 상태에서만 동작한다.
  useEffect(() => {
    if (!session) {
      return
    }
    void refresh()
    const timer = setInterval(() => void refresh(), REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [session, refresh])

  const dateLabel = formatDate(new Date(), {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  })

  return (
    <main className={session ? 'container' : 'container wide'}>
      <header className="header">
        <div className="header-top">
          <div>
            <h1 className="brand">
              Wellness <span>Game</span>
            </h1>
            <p className="date">{dateLabel}</p>
          </div>
          <LanguageToggle />
        </div>
      </header>

      <ErrorBanner message={error} />

      {session ? (
        <>
          <CharacterCard character={character} />
          <TodayActivities
            date={date}
            onDateChange={setDate}
            activities={activities}
            onRefresh={() => void refresh()}
            loading={loading}
          />
          <AccountSection session={session} onLogout={signOut} />
        </>
      ) : completingLogin ? null : (
        <Landing account={<AccountSection session={null} onLogout={signOut} />} />
      )}
    </main>
  )
}
