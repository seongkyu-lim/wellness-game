import { useCallback, useEffect, useState } from 'react'
import { fetchCharacter, fetchDailyActivities } from './api/client'
import type { ActivityEntry, Character } from './api/types'
import { CharacterCard } from './components/CharacterCard'
import { TodayActivities } from './components/TodayActivities'
import { loadUserId, saveUserId } from './lib/userId'

const REFRESH_INTERVAL_MS = 30_000

export default function App() {
  const [userId, setUserId] = useState(loadUserId)
  const [userIdDraft, setUserIdDraft] = useState('')
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [character, setCharacter] = useState<Character | null>(null)
  const [activities, setActivities] = useState<ActivityEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

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

  function applyUserId() {
    const next = userIdDraft.trim()
    if (!next) {
      return
    }
    saveUserId(next)
    setUserId(next)
    setUserIdDraft('')
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

      <section className="card" aria-label="계정">
        <h2 className="section-title">👤 계정</h2>
        <p className="hint">
          데이터 수집은 iPhone 앱이 HealthKit에서 자동으로 처리하고, 웹은 같은 서버의 캐릭터를 보여줍니다. 앱과 같은
          캐릭터를 보려면 동일한 사용자 ID를 사용해야 해요.
        </p>
        <p className="mono">{userId}</p>
        <div className="userid-row">
          <input
            placeholder="앱과 같은 사용자 ID 붙여넣기 (예: guest:...)"
            value={userIdDraft}
            onChange={(e) => setUserIdDraft(e.target.value)}
          />
          <button
            className="btn btn-secondary"
            style={{ width: 'auto' }}
            onClick={applyUserId}
            disabled={!userIdDraft.trim()}
          >
            적용
          </button>
        </div>
      </section>
    </main>
  )
}
