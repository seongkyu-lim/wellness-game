import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActivityEntry } from '../api/types'
import { I18nProvider } from '../i18n/I18nProvider'
import { KST_EARLY_MORNING, KST_NOON, useTimeZone } from '../test/timezone'
import { TodayActivities } from './TodayActivities'

function entry(overrides: Partial<ActivityEntry>): ActivityEntry {
  return {
    type: 'STEPS',
    source: 'HEALTHKIT',
    durationMinutes: null,
    calories: null,
    distanceMeters: null,
    steps: null,
    sleepMinutes: null,
    sleepScore: null,
    gainedXp: 0,
    startedAt: null,
    endedAt: null,
    ...overrides,
  }
}

function renderActivities(props: Partial<Parameters<typeof TodayActivities>[0]> = {}) {
  const handlers = { onDateChange: vi.fn(), onRefresh: vi.fn() }
  const view = render(
    <I18nProvider>
      <TodayActivities date="2026-10-21" activities={[]} loading={false} {...handlers} {...props} />
    </I18nProvider>,
  )
  return { ...view, ...handlers }
}

/** 퀘스트 한 줄의 제목·부제·XP 칩 */
function quests() {
  return Array.from(document.querySelectorAll('.quest')).map((q) => ({
    title: q.querySelector('.quest-title')?.textContent,
    sub: q.querySelector('.quest-sub')?.textContent,
    xp: q.querySelector('.chip')?.textContent ?? null,
  }))
}

beforeEach(() => localStorage.setItem('wellness.lang', 'ko'))

describe('TodayActivities 표시', () => {
  it('기록이 없으면 빈 상태 안내를 보이고 퀘스트 목록을 그리지 않는다', () => {
    renderActivities()
    expect(screen.getByRole('heading', { name: '아직 동기화된 기록이 없어요' })).toBeTruthy()
    expect(quests()).toEqual([])
  })

  it('걸음·수면·운동을 사람이 읽는 단위로 그리고 XP 합계를 보인다', () => {
    renderActivities({
      activities: [
        entry({ type: 'STEPS', steps: 12_345, gainedXp: 12 }),
        entry({ type: 'SLEEP', sleepMinutes: 425, sleepScore: 88, gainedXp: 20 }),
        entry({ type: 'WORKOUT', source: 'RUNNING', durationMinutes: 30, calories: 250.6, distanceMeters: 5_040, gainedXp: 15 }),
      ],
    })
    expect(quests()).toEqual([
      { title: '걸음 수', sub: '12,345걸음', xp: '+12 XP' },
      { title: '수면 7시간 5분', sub: '수면 점수 88', xp: '+20 XP' },
      { title: '달리기 30분', sub: '251 kcal · 5.0 km', xp: '+15 XP' },
    ])
    expect(document.querySelector('.quest-total .chip')?.textContent).toBe('+47 XP')
  })

  it('값이 빠진 기록은 기록 없음·점수 없음으로, XP가 0이면 칩 없이 그린다', () => {
    renderActivities({ activities: [entry({ type: 'WORKOUT', source: 'SWIMMING', gainedXp: 0 })] })
    expect(quests()).toEqual([
      { title: '걸음 수', sub: '기록 없음', xp: null },
      { title: '수면 기록 없음', sub: '수면 점수 없음', xp: null },
      // 시간·칼로리·거리가 모두 없으면 출처(Apple 건강)를 부제로 쓴다.
      { title: '수영', sub: 'Apple 건강', xp: null },
    ])
  })

  it('알 수 없는 운동 종류는 일반 운동으로 보이고 0 이하 칼로리·거리는 생략한다', () => {
    renderActivities({
      activities: [
        entry({ type: 'WORKOUT', source: 'YOGA', durationMinutes: 45, calories: 0, distanceMeters: 1_200, gainedXp: 3 }),
        entry({ type: 'WORKOUT', source: 'CYCLING', calories: 120, distanceMeters: 0, startedAt: '2026-10-21T07:00:00Z' }),
      ],
    })
    expect(quests().slice(2)).toEqual([
      { title: '운동 45분', sub: '1.2 km', xp: '+3 XP' },
      { title: '자전거', sub: '120 kcal', xp: null },
    ])
  })

  it('영어에서는 단위와 문구가 영어 사전을 따른다', () => {
    localStorage.setItem('wellness.lang', 'en')
    renderActivities({
      activities: [entry({ type: 'STEPS', steps: 9_876 }), entry({ type: 'SLEEP', sleepMinutes: 61 })],
    })
    expect(quests().map((q) => q.sub)).toEqual(['9,876 steps', 'No sleep score'])
    expect(quests()[1].title).toBe('Sleep 1h 1m')
  })
})

describe('TodayActivities 조작', () => {
  it('날짜를 고르면 onDateChange로 알린다', () => {
    const { onDateChange } = renderActivities()
    fireEvent.change(screen.getByLabelText('조회 날짜'), { target: { value: '2026-10-01' } })
    expect(onDateChange).toHaveBeenCalledWith('2026-10-01')
  })

  it('새로고침 버튼은 onRefresh를 부르고, 조회 중에는 막힌다', () => {
    const { onRefresh, rerender } = renderActivities()
    act(() => screen.getByRole('button', { name: '새로고침' }).click())
    expect(onRefresh).toHaveBeenCalledTimes(1)

    rerender(
      <I18nProvider>
        <TodayActivities date="2026-10-21" activities={[]} loading onDateChange={vi.fn()} onRefresh={onRefresh} />
      </I18nProvider>,
    )
    const button = screen.getByRole<HTMLButtonElement>('button', { name: '새로고침' })
    expect(button.disabled).toBe(true)
    act(() => button.click())
    expect(onRefresh).toHaveBeenCalledTimes(1)
  })
})

describe('조회 날짜 최댓값(로컬 오늘)', () => {
  useTimeZone('Asia/Seoul')
  afterEach(() => vi.useRealTimers())

  const maxAt = (now: Date) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(now)
    renderActivities()
    return screen.getByLabelText<HTMLInputElement>('조회 날짜').max
  }

  it('한국 시간 낮에는 오늘 날짜가 최댓값이다', () => {
    expect(maxAt(KST_NOON)).toBe('2026-10-22')
  })

  // 버그: 오늘을 toISOString()(UTC)으로 구해 KST 00:00~09:00에는 최댓값이 전날이 되어 오늘을 고를 수 없다.
  // 고치면 이 테스트가 통과하므로 it.fails를 it으로 바꾼다.
  it.fails('한국 시간 새벽(UTC로는 전날)에도 최댓값은 로컬 오늘이어야 한다', () => {
    expect(maxAt(KST_EARLY_MORNING)).toBe('2026-10-22')
  })
})
