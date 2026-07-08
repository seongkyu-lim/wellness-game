import { useState } from 'react'
import type { ActivityPayload, WorkoutType } from '../api/types'

const WORKOUT_OPTIONS: { value: WorkoutType; label: string }[] = [
  { value: 'RUNNING', label: '달리기' },
  { value: 'WALKING', label: '걷기' },
  { value: 'CYCLING', label: '자전거' },
  { value: 'SWIMMING', label: '수영' },
  { value: 'STRENGTH_TRAINING', label: '근력 운동' },
  { value: 'OTHER', label: '기타' },
]

interface Props {
  disabled: boolean
  onSync: (date: string, activities: ActivityPayload[]) => void
}

export function ActivityForm({ disabled, onSync }: Props) {
  const today = new Date().toISOString().slice(0, 10)
  const [date, setDate] = useState(today)
  const [steps, setSteps] = useState('')
  const [sleepHours, setSleepHours] = useState('')
  const [wakeTime, setWakeTime] = useState('08:00')
  const [workoutType, setWorkoutType] = useState<WorkoutType>('RUNNING')
  const [workoutMinutes, setWorkoutMinutes] = useState('')
  const [workoutStart, setWorkoutStart] = useState('18:00')
  const [calories, setCalories] = useState('')

  const activities = buildActivities()

  function buildActivities(): ActivityPayload[] {
    const result: ActivityPayload[] = []

    const stepCount = Number(steps)
    if (stepCount > 0) {
      result.push({ type: 'STEPS', steps: stepCount })
    }

    const sleepMinutes = Math.round(Number(sleepHours) * 60)
    if (sleepMinutes > 0) {
      const endedAt = new Date(`${date}T${wakeTime}`)
      const startedAt = new Date(endedAt.getTime() - sleepMinutes * 60_000)
      result.push({
        type: 'SLEEP',
        sleepMinutes,
        startedAt: startedAt.toISOString(),
        endedAt: endedAt.toISOString(),
      })
    }

    const minutes = Number(workoutMinutes)
    if (minutes > 0) {
      const startedAt = new Date(`${date}T${workoutStart}`)
      const endedAt = new Date(startedAt.getTime() + minutes * 60_000)
      result.push({
        type: 'WORKOUT',
        workoutType,
        durationMinutes: minutes,
        calories: Number(calories) > 0 ? Number(calories) : undefined,
        startedAt: startedAt.toISOString(),
        endedAt: endedAt.toISOString(),
      })
    }

    return result
  }

  return (
    <form
      className="card"
      onSubmit={(event) => {
        event.preventDefault()
        onSync(date, activities)
      }}
    >
      <h2 className="section-title">☀️ 오늘의 활동 기록</h2>
      <div className="form-grid">
        <div className="field full">
          <label htmlFor="date">날짜</label>
          <input id="date" type="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="steps">걸음 수</label>
          <input
            id="steps"
            type="number"
            min="0"
            placeholder="예: 8500"
            value={steps}
            onChange={(e) => setSteps(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="sleep">수면 시간 (시간)</label>
          <input
            id="sleep"
            type="number"
            min="0"
            step="0.5"
            placeholder="예: 7.5"
            value={sleepHours}
            onChange={(e) => setSleepHours(e.target.value)}
          />
        </div>
        {Number(sleepHours) > 0 && (
          <div className="field">
            <label htmlFor="wake">기상 시각</label>
            <input id="wake" type="time" value={wakeTime} onChange={(e) => setWakeTime(e.target.value)} />
          </div>
        )}
        <div className="field">
          <label htmlFor="workout-type">운동 종류</label>
          <select id="workout-type" value={workoutType} onChange={(e) => setWorkoutType(e.target.value as WorkoutType)}>
            {WORKOUT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="workout-minutes">운동 시간 (분)</label>
          <input
            id="workout-minutes"
            type="number"
            min="0"
            placeholder="예: 30"
            value={workoutMinutes}
            onChange={(e) => setWorkoutMinutes(e.target.value)}
          />
        </div>
        {Number(workoutMinutes) > 0 && (
          <>
            <div className="field">
              <label htmlFor="workout-start">운동 시작 시각</label>
              <input
                id="workout-start"
                type="time"
                value={workoutStart}
                onChange={(e) => setWorkoutStart(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="calories">소모 칼로리 (kcal, 선택)</label>
              <input
                id="calories"
                type="number"
                min="0"
                placeholder="예: 250"
                value={calories}
                onChange={(e) => setCalories(e.target.value)}
              />
            </div>
          </>
        )}
        <div className="field full">
          <button className="btn btn-primary" type="submit" disabled={disabled || activities.length === 0}>
            서버에 동기화
          </button>
          {activities.length === 0 && <p className="hint">걸음 수, 수면, 운동 중 하나 이상 입력하면 동기화할 수 있어요.</p>}
        </div>
      </div>
    </form>
  )
}
