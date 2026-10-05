import type { ReactNode } from 'react'
import type { ActivityEntry } from '../api/types'
import { CharacterAvatar } from './CharacterAvatar'
import { IconBike, IconMoon, IconRun, IconStrength, IconWalk, IconWave } from './Icons'

const WORKOUT_LABELS: Record<string, { name: string; icon: ReactNode }> = {
  SWIMMING: { name: '수영', icon: <IconWave size={24} /> },
  RUNNING: { name: '달리기', icon: <IconRun size={24} /> },
  WALKING: { name: '걷기', icon: <IconWalk size={24} /> },
  CYCLING: { name: '자전거', icon: <IconBike size={24} /> },
  STRENGTH_TRAINING: { name: '근력 운동', icon: <IconStrength size={24} /> },
  OTHER: { name: '운동', icon: <IconRun size={24} /> },
}

export function sleepDurationText(minutes: number | null): string {
  if (minutes == null) {
    return '기록 없음'
  }
  return `${Math.floor(minutes / 60)}시간 ${minutes % 60}분`
}

function workoutDetail(workout: ActivityEntry): string {
  const parts: string[] = []
  if (workout.calories != null && Number(workout.calories) > 0) {
    parts.push(`${Math.round(Number(workout.calories))} kcal`)
  }
  if (workout.distanceMeters != null && workout.distanceMeters > 0) {
    parts.push(`${(workout.distanceMeters / 1000).toFixed(1)} km`)
  }
  return parts.length > 0 ? parts.join(' · ') : 'Apple 건강'
}

interface QuestProps {
  icon: ReactNode
  tint: string
  title: string
  sub: string
  xp?: number
}

function Quest({ icon, tint, title, sub, xp }: QuestProps) {
  return (
    <div className="quest">
      <div className="quest-icon" style={{ background: tint }}>
        {icon}
      </div>
      <div className="quest-body">
        <span className="quest-title">{title}</span>
        <span className="quest-sub">{sub}</span>
      </div>
      {xp != null && xp > 0 && <span className="chip">+{xp} XP</span>}
    </div>
  )
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
        <h2 className="section-title">오늘의 퀘스트</h2>
        <div className="section-tools">
          <input
            type="date"
            aria-label="조회 날짜"
            value={date}
            max={today}
            onChange={(e) => onDateChange(e.target.value)}
          />
          <button className="btn btn-compact" onClick={onRefresh} disabled={loading}>
            새로고침
          </button>
        </div>
      </div>

      {activities.length === 0 ? (
        <div className="empty-state">
          <CharacterAvatar level={1} size={64} />
          <div>
            <h3>아직 동기화된 기록이 없어요</h3>
            <p className="hint">
              iPhone 앱이 HealthKit 데이터를 자동으로 수집해요. 앱에서 동기화하면 이곳에 바로 표시됩니다.
            </p>
          </div>
        </div>
      ) : (
        <div className="quest-list">
          <Quest
            icon={<IconWalk size={24} />}
            tint="var(--mint)"
            title="걸음 수"
            sub={steps?.steps != null ? `${steps.steps.toLocaleString()}걸음` : '기록 없음'}
            xp={steps?.gainedXp}
          />
          <Quest
            icon={<IconMoon size={24} />}
            tint="var(--lilac)"
            title={`수면 ${sleepDurationText(sleep?.sleepMinutes ?? null)}`}
            sub={sleep?.sleepScore != null ? `수면 점수 ${sleep.sleepScore}` : '수면 점수 없음'}
            xp={sleep?.gainedXp}
          />
          {workouts.map((workout, index) => {
            const label = WORKOUT_LABELS[workout.source] ?? WORKOUT_LABELS.OTHER
            return (
              <Quest
                key={`${workout.source}-${workout.startedAt ?? index}`}
                icon={label.icon}
                tint="var(--peach)"
                title={workout.durationMinutes != null ? `${label.name} ${workout.durationMinutes}분` : label.name}
                sub={workoutDetail(workout)}
                xp={workout.gainedXp}
              />
            )
          })}
          <div className="quest-total">
            <span className="hint">이 날짜에 얻은 XP</span>
            <span className="chip green">+{activities.reduce((sum, a) => sum + a.gainedXp, 0)} XP</span>
          </div>
        </div>
      )}
    </section>
  )
}
