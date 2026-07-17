import { useCallback, useEffect, useState } from 'react'
import { fetchCharacter, fetchDailyActivities } from './api/client'
import type { ActivityEntry, Character } from './api/types'
import { AccountSection } from './components/AccountSection'
import { CharacterCard } from './components/CharacterCard'
import { TodayActivities } from './components/TodayActivities'
import { clearSession, completeLoginFromRedirect, loadSession, type Session } from './lib/auth'
import { loadUserId, saveUserId } from './lib/userId'

const REFRESH_INTERVAL_MS = 30_000

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession)
  const [guestId, setGuestId] = useState(loadUserId)
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [character, setCharacter] = useState<Character | null>(null)
  const [activities, setActivities] = useState<ActivityEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 로그인하면 provider 계정 ID, 아니면 게스트(또는 수동 입력) ID
  const userId = session?.userId ?? guestId

  // 소셜 로그인 리다이렉트로 돌아온 경우 code를 세션으로 교환한다.
  useEffect(() => {
    completeLoginFromRedirect()
      .then((newSession) => {
        if (newSession) {
          setSession(newSession)
        }
      })
      .catch((e) => setError(e instanceof Error ? e.message : '로그인에 실패했습니다.'))
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [summary, daily] = await Promise.all([fetchCharacter(userId), fetchDailyActivities(userId, date)])
      setCharacter(summary?.character ?? null)
      setActivities(daily.activities)
    } catch (e) {
      setError(e instanceof Error ? e.message : '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.')
    } finally {
      setLoading(false)
    }
  }, [userId, date])

  // 앱이 동기화하면 자동으로 반영되도록 주기적으로 새로고침한다.
  useEffect(() => {
    void refresh()
    const timer = setInterval(() => void refresh(), REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [refresh])

  function handleLogout() {
    clearSession()
    setSession(null)
    // iOS와 동일하게 기존 게스트 캐릭터로 복귀한다.
  }

  function handleApplyManualId(next: string) {
    if (!next) {
      return
    }
    saveUserId(next)
    setGuestId(next)
  }

  const dateLabel = new Date().toLocaleDateString('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  })

  return (
    <main className="container">
      <header className="header">
        <p className="date">{dateLabel}</p>
        <h1>오늘도 한 뼘 성장해요 🌱</h1>
      </header>

      {error && (
        <div className="error-banner" role="alert">
          {error}
        </div>
      )}

      <CharacterCard character={character} />
      <TodayActivities
        date={date}
        onDateChange={setDate}
        activities={activities}
        onRefresh={() => void refresh()}
        loading={loading}
      />
      <AccountSection session={session} userId={userId} onLogout={handleLogout} onApplyManualId={handleApplyManualId} />
    </main>
  )
}
