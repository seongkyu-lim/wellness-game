import { act, fireEvent, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Language } from '../i18n/core'
import { activityEntry } from '../test/fixtures'
import { renderWithI18n } from '../test/render'
import { KST_EARLY_MORNING, KST_NOON, useTimeZone } from '../test/timezone'
import { TodayActivities } from './TodayActivities'

type Props = Parameters<typeof TodayActivities>[0]

function renderActivities(props: Partial<Props> = {}, lang: Language = 'ko') {
  const handlers = { onDateChange: vi.fn(), onRefresh: vi.fn() }
  const view = renderWithI18n(<TodayActivities date="2026-10-21" activities={[]} loading={false} {...handlers} {...props} />, {
    lang,
  })
  return { ...view, ...handlers }
}

/** 활동 기록 영역(aria-label로 찾는다). */
const section = (name = '활동 기록') => screen.getByRole('region', { name })
/** 영역 안의 XP 칩 문구(+N XP), 화면 순서대로. 마지막은 합계다. */
const xpChips = () => within(section()).queryAllByText(/^\+\d+ XP$/).map((el) => el.textContent)

/** 문구들이 영역 안에서 주어진 순서대로 나온다. */
function expectInOrder(texts: string[]) {
  const content = section().textContent ?? ''
  const positions = texts.map((text) => content.indexOf(text))
  expect(positions.every((p) => p >= 0)).toBe(true)
  expect([...positions].sort((a, b) => a - b)).toEqual(positions)
}

describe('TodayActivities 표시', () => {
  it('기록이 없으면 빈 상태 안내를 보이고 퀘스트·XP 합계를 그리지 않는다', () => {
    renderActivities()
    expect(screen.getByRole('heading', { name: '아직 동기화된 기록이 없어요' })).toBeTruthy()
    expect(within(section()).queryByText('걸음 수')).toBeNull()
    expect(xpChips()).toEqual([])
  })

  it('걸음·수면·운동을 사람이 읽는 단위로 그리고 XP 합계를 보인다', () => {
    renderActivities({
      activities: [
        activityEntry({ type: 'STEPS', steps: 12_345, gainedXp: 12 }),
        activityEntry({ type: 'SLEEP', sleepMinutes: 425, sleepScore: 88, gainedXp: 20 }),
        activityEntry({ type: 'WORKOUT', source: 'RUNNING', durationMinutes: 30, calories: 250.6, distanceMeters: 5_040, gainedXp: 15 }),
      ],
    })
    expectInOrder(['걸음 수', '12,345걸음', '수면 7시간 5분', '수면 점수 88', '달리기 30분', '251 kcal · 5.0 km'])
    expect(xpChips()).toEqual(['+12 XP', '+20 XP', '+15 XP', '+47 XP'])
  })

  it('값이 빠진 기록은 기록 없음·점수 없음으로, XP가 0이면 칩 없이 그린다', () => {
    renderActivities({ activities: [activityEntry({ type: 'WORKOUT', source: 'SWIMMING' })] })
    expect(screen.getByText('기록 없음')).toBeTruthy()
    expect(screen.getByText('수면 기록 없음')).toBeTruthy()
    expect(screen.getByText('수면 점수 없음')).toBeTruthy()
    // 시간·칼로리·거리가 모두 없으면 출처(Apple 건강)를 부제로 쓴다.
    expectInOrder(['수영', 'Apple 건강'])
    expect(xpChips()).toEqual(['+0 XP']) // 합계만 남는다.
  })

  it('알 수 없는 운동 종류는 일반 운동으로 보이고 0 이하 칼로리·거리는 생략한다', () => {
    renderActivities({
      activities: [
        activityEntry({ type: 'WORKOUT', source: 'YOGA', durationMinutes: 45, calories: 0, distanceMeters: 1_200, gainedXp: 3 }),
        activityEntry({ type: 'WORKOUT', source: 'CYCLING', calories: 120, distanceMeters: 0, startedAt: '2026-10-21T07:00:00Z' }),
      ],
    })
    expect(screen.getByText('운동 45분')).toBeTruthy()
    expect(screen.getByText('1.2 km')).toBeTruthy()
    expect(screen.getByText('자전거')).toBeTruthy()
    expect(screen.getByText('120 kcal')).toBeTruthy()
    expect(screen.queryByText(/(^|· )0 kcal/)).toBeNull()
    expect(screen.queryByText(/\b0\.0 km/)).toBeNull()
  })

  it('영어에서는 단위와 문구가 영어 사전을 따른다', () => {
    renderActivities({ activities: [activityEntry({ type: 'STEPS', steps: 9_876 }), activityEntry({ type: 'SLEEP', sleepMinutes: 61 })] }, 'en')
    const region = screen.getByRole('region', { name: 'Activity' })
    expect(within(region).getByText('9,876 steps')).toBeTruthy()
    expect(within(region).getByText('Sleep 1h 1m')).toBeTruthy()
    expect(within(region).getByText('No sleep score')).toBeTruthy()
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

    rerender(<TodayActivities date="2026-10-21" activities={[]} loading onDateChange={vi.fn()} onRefresh={onRefresh} />)
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
