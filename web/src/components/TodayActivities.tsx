import type { ActivityEntry } from '../api/types'

const WORKOUT_LABELS: Record<string, { name: string; emoji: string }> = {
  SWIMMING: { name: '수영', emoji: '🏊' },
  RUNNING: { name: '달리기', emoji: '🏃' },
  WALKING: { name: '걷기', emoji: '🚶' },
  CYCLING: { name: '자전거', emoji: '🚴' },
  STRENGTH_TRAINING: { name: '근력 운동', emoji: '🏋️' },
  OTHER: { name: '운동', emoji: '💪' },
}

export function sleepDurationText(minutes: number | null): string {
  if (minutes == null) {
    return '기록 없음'
  }
  return `${Math.floor(minutes / 60)}시간 ${minutes % 60}분`
}

interface Props {
  date: string
  onDateChange: (date: string) => void
  activities: ActivityEntry[]
  onRefresh: () => void
  loading: boolean
}

export function TodayActivities({ date, onDateChange, activities, onRefresh, loading }: Props) {
  const today = new Date().toISOString().slice(0, 10)
  const steps = activities.find((a) => a.type === 'STEPS')
  const sleep = activities.find((a) => a.type === 'SLEEP')
  const workouts = activities.filter((a) => a.type === 'WORKOUT')

  return (
    <section className="card" aria-label="활동 기록">
      <div className="section-head">
        <h2 className="section-title">☀️ 활동 기록</h2>
        <div className="section-tools">
          <input
            type="date"
            aria-label="조회 날짜"
            value={date}
            max={today}
            onChange={(e) => onDateChange(e.target.value)}
          />
          <button className="btn btn-secondary btn-compact" onClick={onRefresh} disabled={loading}>
            새로고침
          </button>
        </div>
      </div>

      {activities.length === 0 ? (
        <div className="empty-state">
          <span className="emoji" aria-hidden>
            📱
          </span>
          <div>
            <h2>아직 동기화된 기록이 없어요</h2>
            <p>
              iPhone 앱이 HealthKit 데이터를 자동으로 수집해요. 앱에서 &lsquo;서버에 동기화&rsquo;를 누르면 이곳에
              바로 표시됩니다.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="metric-grid">
            <div className="metric-card">
              <span className="metric-icon" aria-hidden>
                👟
              </span>
              <span className="metric-value">{steps?.steps?.toLocaleString() ?? '기록 없음'}</span>
              <span className="metric-name">걸음 수</span>
            </div>
            <div className="metric-card">
              <span className="metric-icon" aria-hidden>
                🌙
              </span>
              <span className="metric-value">{sleepDurationText(sleep?.sleepMinutes ?? null)}</span>
              <span className="metric-name">수면</span>
            </div>
          </div>

          {workouts.map((workout, index) => {
            const label = WORKOUT_LABELS[workout.source] ?? WORKOUT_LABELS.OTHER
            return (
              <div className="result-row" key={`${workout.source}-${workout.startedAt ?? index}`}>
                <span className="message">
                  {label.emoji} {label.name}
                  {workout.durationMinutes != null && ` · ${workout.durationMinutes}분`}
                  {workout.calories != null && Number(workout.calories) > 0 && ` · ${Math.round(Number(workout.calories))} kcal`}
                </span>
                <span className="pill lime">+{workout.gainedXp} XP</span>
              </div>
            )
          })}

          <div className="result-row">
            <span className="message hint">이 날짜에 얻은 XP</span>
            <span className="pill lime">+{activities.reduce((sum, a) => sum + a.gainedXp, 0)} XP</span>
          </div>
        </>
      )}
    </section>
  )
}
