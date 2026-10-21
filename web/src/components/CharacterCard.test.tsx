import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { Character } from '../api/types'
import { I18nProvider } from '../i18n/I18nProvider'
import { CharacterCard, xpProgress } from './CharacterCard'

function character(overrides: Partial<Character> = {}): Character {
  return {
    level: 4,
    currentXp: 30,
    totalXp: 12_345,
    nextLevelXp: 120,
    stats: { str: 11, vit: 12, intStat: 13, discipline: 14, recovery: 15 },
    ...overrides,
  }
}

function renderCard(value: Character | null, lang: 'ko' | 'en' = 'ko') {
  localStorage.setItem('wellness.lang', lang)
  return render(
    <I18nProvider>
      <CharacterCard character={value} />
    </I18nProvider>,
  )
}

beforeEach(() => localStorage.clear())

describe('xpProgress', () => {
  it.each([
    [30, 120, 0.25],
    [120, 120, 1],
    [500, 120, 1], // 서버 값이 앞서 있어도 막대가 넘치지 않는다.
    [-5, 120, 0],
    [10, 0, 0], // 다음 레벨 XP가 0이면 0으로 나누지 않는다.
  ])('현재 %i / 다음 %i → %f', (currentXp, nextLevelXp, expected) => {
    expect(xpProgress(character({ currentXp, nextLevelXp }))).toBe(expected)
  })
})

describe('CharacterCard', () => {
  it('아직 동기화 전이면 안내 말풍선만 보이고 경험치·스탯을 그리지 않는다', () => {
    renderCard(null)
    expect(screen.getByText('아직 동기화 전')).toBeTruthy()
    expect(screen.getByText('iPhone 앱에서 동기화하면 나도 깨어날게!')).toBeTruthy()
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(screen.queryByRole('region', { name: '스탯' })).toBeNull()
  })

  it('레벨로 성장 단계와 다음 단계 안내를 정한다', () => {
    renderCard(character({ level: 4 }))
    expect(screen.getByText('Lv.4 · 새싹 단계')).toBeTruthy()
    expect(screen.getByText('Lv.6이 되면 줄기 단계로 자랄 거야!')).toBeTruthy()
    expect(screen.getByText('줄기까지 Lv.6')).toBeTruthy()
  })

  it('경험치 막대는 접근 가능한 값(백분율·원래 수치)을 노출한다', () => {
    renderCard(character({ currentXp: 1_050, nextLevelXp: 4_000 }))
    const bar = screen.getByRole('progressbar', { name: '다음 레벨까지 경험치' })
    expect(bar.getAttribute('aria-valuenow')).toBe('26')
    expect(bar.getAttribute('aria-valuetext')).toBe('1,050 / 4,000 XP')
    expect(screen.getByText('누적 12,345 XP')).toBeTruthy()
  })

  it('스탯 네 개를 순서대로 보이고 INT는 표시하지 않는다', () => {
    renderCard(character())
    const tiles = Array.from(screen.getByRole('region', { name: '스탯' }).querySelectorAll('.stat-tile'))
    expect(tiles.map((tile) => [tile.querySelector('.stat-value')?.textContent, tile.querySelector('.stat-name')?.textContent])).toEqual([
      ['11', 'STR 근력'],
      ['12', 'VIT 활력'],
      ['14', 'DISC 절제'],
      ['15', 'REC 회복'],
    ])
  })

  it('최종 단계에서는 다음 단계 대신 완료 문구를 보인다', () => {
    renderCard(character({ level: 25 }), 'en')
    expect(screen.getByText('Lv.25 · Blossom stage')).toBeTruthy()
    expect(screen.getByText("I'm in full bloom! Thanks for always being with me!")).toBeTruthy()
    expect(screen.getByText('Final stage')).toBeTruthy()
  })
})
