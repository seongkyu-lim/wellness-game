import type { ReactNode } from 'react'
import type { ActivityEntry } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { CharacterAvatar } from './CharacterAvatar'
import { Quest } from './Quest'
import { IconBike, IconMoon, IconRun, IconStrength, IconWalk, IconWave } from './Icons'

const WORKOUT_ICONS = {
  SWIMMING: <IconWave size={24} />,
  RUNNING: <IconRun size={24} />,
  WALKING: <IconWalk size={24} />,
  CYCLING: <IconBike size={24} />,
  STRENGTH_TRAINING: <IconStrength size={24} />,
  OTHER: <IconRun size={24} />,
} as const satisfies Record<string, ReactNode>

type WorkoutKey = keyof typeof WORKOUT_ICONS

function workoutKey(source: string): WorkoutKey {
  return source in WORKOUT_ICONS ? (source as WorkoutKey) : 'OTHER'
}

function workoutDetail(workout: ActivityEntry, fallback: string): string {
  const parts: string[] = []
  if (workout.calories != null && Number(workout.calories) > 0) {
    parts.push(`${Math.round(Number(workout.calories))} kcal`)
  }
  if (workout.distanceMeters != null && workout.distanceMeters > 0) {
    parts.push(`${(workout.distanceMeters / 1000).toFixed(1)} km`)
  }
  return parts.length > 0 ? parts.join(' · ') : fallback
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
          <button className="btn btn-compact" onClick={onRefresh} disabled={loading}>
            {t('activity.refresh')}
          </button>
        </div>
      </div>

      {activities.length === 0 ? (
        <div className="empty-state">
          <CharacterAvatar level={1} size={64} decorative />
          <div>
            <h3>{t('activity.emptyTitle')}</h3>
            <p className="hint">{t('activity.emptyBody')}</p>
          </div>
        </div>
      ) : (
        <div className="quest-list">
          <Quest
            icon={<IconWalk size={24} />}
            tint="var(--mint)"
            title={t('activity.steps')}
            sub={steps?.steps != null ? t('activity.stepsCount', { n: formatNumber(steps.steps) }) : t('activity.noRecord')}
            xp={steps?.gainedXp}
          />
          <Quest
            icon={<IconMoon size={24} />}
            tint="var(--lilac)"
            title={t('activity.sleepTitle', { duration: sleepText })}
            sub={sleep?.sleepScore != null ? t('activity.sleepScore', { score: sleep.sleepScore }) : t('activity.noSleepScore')}
            xp={sleep?.gainedXp}
          />
          {workouts.map((workout, index) => {
            const key = workoutKey(workout.source)
            return (
              <Quest
                key={`${workout.source}-${workout.startedAt ?? index}`}
                icon={WORKOUT_ICONS[key]}
                tint="var(--peach)"
                title={
                  workout.durationMinutes != null
                    ? `${t(`workout.${key}`)} ${t('activity.minutes', { n: workout.durationMinutes })}`
                    : t(`workout.${key}`)
                }
                sub={workoutDetail(workout, t('activity.healthSource'))}
                xp={workout.gainedXp}
              />
            )
          })}
          <div className="quest-total">
            <span className="hint">{t('activity.totalXp')}</span>
            <span className="chip green">+{activities.reduce((sum, a) => sum + a.gainedXp, 0)} XP</span>
          </div>
        </div>
      )}
    </section>
  )
}
