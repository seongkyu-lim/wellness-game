import { useCallback, useEffect, useState } from 'react'
import { fetchCharacter, syncActivities } from './api/client'
import type { ActivityPayload, Character, SyncResponse } from './api/types'
import { ActivityForm } from './components/ActivityForm'
import { CharacterCard } from './components/CharacterCard'
import { ResultsList } from './components/ResultsList'
import { loadUserId, saveUserId } from './lib/userId'

export default function App() {
  const [userId, setUserId] = useState(loadUserId)
  const [userIdDraft, setUserIdDraft] = useState('')
  const [character, setCharacter] = useState<Character | null>(null)
  const [lastSync, setLastSync] = useState<SyncResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadCharacter = useCallback(async (id: string) => {
    setLoading(true)
    setError(null)
    try {
      const summary = await fetchCharacter(id)
      setCharacter(summary?.character ?? null)
    } catch (e) {
      setError(e instanceof Error ? e.message : '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadCharacter(userId)
  }, [userId, loadCharacter])

  async function handleSync(date: string, activities: ActivityPayload[]) {
    setLoading(true)
    setError(null)
    try {
      const response = await syncActivities({ userId, date, activities })
      setCharacter(response.character)
      setLastSync(response)
    } catch (e) {
      setError(e instanceof Error ? e.message : '동기화에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  function applyUserId() {
    const next = userIdDraft.trim()
    if (!next) {
      return
    }
    saveUserId(next)
    setUserId(next)
    setUserIdDraft('')
    setLastSync(null)
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

      <CharacterCard character={character} lastSync={lastSync} />
      <ActivityForm disabled={loading} onSync={handleSync} />
      <ResultsList results={lastSync?.activityResults ?? []} />

      <section className="card" aria-label="계정">
        <h2 className="section-title">👤 계정</h2>
        <p className="hint">
          웹은 iOS 앱과 같은 서버·같은 캐릭터를 사용합니다. 아래 사용자 ID가 캐릭터의 열쇠예요. 다른 기기와 같은
          캐릭터를 보려면 동일한 ID를 입력하세요.
        </p>
        <p className="mono">{userId}</p>
        <div className="userid-row">
          <input
            className="field-input"
            placeholder="다른 사용자 ID 붙여넣기 (예: guest:...)"
            value={userIdDraft}
            onChange={(e) => setUserIdDraft(e.target.value)}
          />
          <button className="btn btn-secondary" style={{ width: 'auto' }} onClick={applyUserId} disabled={!userIdDraft.trim()}>
            적용
          </button>
        </div>
      </section>
    </main>
  )
}
