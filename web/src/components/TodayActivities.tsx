import type { ActivityEntry } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'

const WORKOUT_EMOJIS = {
  SWIMMING: '🏊',
  RUNNING: '🏃',
  WALKING: '🚶',
  CYCLING: '🚴',
  STRENGTH_TRAINING: '🏋️',
  OTHER: '💪',
} as const

type WorkoutKey = keyof typeof WORKOUT_EMOJIS

function workoutKey(source: string): WorkoutKey {
  return source in WORKOUT_EMOJIS ? (source as WorkoutKey) : 'OTHER'
}

interface Props {
  date: string
  onDateChange: (date: string) => void
  activities: ActivityEntry[]
  onRefresh: () => void
  loading: boolean
}

export function TodayActivities({ date, onDateChange, activities, onRefresh, loading }: Props) {
  const { t, formatNumber } = useI18n()
  const today = new Date().toISOString().slice(0, 10)
  const steps = activities.find((a) => a.type === 'STEPS')
  const sleep = activities.find((a) => a.type === 'SLEEP')
  const workouts = activities.filter((a) => a.type === 'WORKOUT')
  const sleepText =
    sleep?.sleepMinutes == null
      ? t('activity.noRecord')
      : t('activity.sleepDuration', {
          hours: Math.floor(sleep.sleepMinutes / 60),
          minutes: sleep.sleepMinutes % 60,
        })

  return (
    <section className="card" aria-label={t('activity.label')}>
      <div className="section-head">
        <h2 className="section-title">{t('activity.title')}</h2>
        <div className="section-tools">
          <input
            type="date"
            aria-label={t('activity.dateLabel')}
            value={date}
            max={today}
            onChange={(e) => onDateChange(e.target.value)}
          />
          <button className="btn btn-secondary btn-compact" onClick={onRefresh} disabled={loading}>
            {t('activity.refresh')}
          </button>
        </div>
      </div>

      {activities.length === 0 ? (
        <div className="empty-state">
          <span className="emoji" aria-hidden>
            📱
          </span>
          <div>
            <h2>{t('activity.emptyTitle')}</h2>
            <p>{t('activity.emptyBody')}</p>
          </div>
        </div>
      ) : (
        <>
          <div className="metric-grid">
            <div className="metric-card">
              <span className="metric-icon" aria-hidden>
                👟
              </span>
              <span className="metric-value">{steps?.steps != null ? formatNumber(steps.steps) : t('activity.noRecord')}</span>
              <span className="metric-name">{t('activity.steps')}</span>
            </div>
            <div className="metric-card">
              <span className="metric-icon" aria-hidden>
                🌙
              </span>
              <span className="metric-value">{sleepText}</span>
              <span className="metric-name">{t('activity.sleep')}</span>
            </div>
          </div>

          {workouts.map((workout, index) => {
            const key = workoutKey(workout.source)
            return (
              <div className="result-row" key={`${workout.source}-${workout.startedAt ?? index}`}>
                <span className="message">
                  {WORKOUT_EMOJIS[key]} {t(`workout.${key}`)}
                  {workout.durationMinutes != null && ` · ${t('activity.minutes', { n: workout.durationMinutes })}`}
                  {workout.calories != null && Number(workout.calories) > 0 && ` · ${Math.round(Number(workout.calories))} kcal`}
                </span>
                <span className="pill lime">+{workout.gainedXp} XP</span>
              </div>
            )
          })}

          <div className="result-row">
            <span className="message hint">{t('activity.totalXp')}</span>
            <span className="pill lime">+{activities.reduce((sum, a) => sum + a.gainedXp, 0)} XP</span>
          </div>
        </>
      )}
    </section>
  )
}
