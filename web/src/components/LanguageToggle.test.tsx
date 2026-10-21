import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { getLanguage } from '../i18n/core'
import { I18nProvider } from '../i18n/I18nProvider'
import { renderWithI18n } from '../test/render'
import { LanguageToggle } from './LanguageToggle'

const renderToggle = () => renderWithI18n(<LanguageToggle />)

const pressed = () =>
  screen.getAllByRole('button').map((b) => [b.textContent, b.getAttribute('aria-pressed')])

describe('LanguageToggle', () => {
  it('현재 언어 버튼만 눌림 상태이고, 그룹 이름과 버튼 설명도 현재 언어로 보인다', () => {
    renderToggle()
    expect(screen.getByRole('group', { name: '언어' })).toBeTruthy()
    expect(pressed()).toEqual([
      ['한', 'true'],
      ['EN', 'false'],
    ])
    // 버튼 글자는 각 언어 자체 표기이므로 스크린리더가 해당 언어로 읽도록 lang을 둔다.
    expect(screen.getByRole('button', { name: 'EN' }).getAttribute('lang')).toBe('en')
  })

  it('EN을 누르면 눌림 상태·그룹 이름·전역 언어가 바뀌고, 다시 그려도 선택이 유지된다', () => {
    const { unmount } = renderToggle()
    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(pressed()).toEqual([
      ['한', 'false'],
      ['EN', 'true'],
    ])
    expect(screen.getByRole('group', { name: 'Language' })).toBeTruthy()
    expect(getLanguage()).toBe('en')

    unmount()
    // 저장값을 덮어쓰지 않고 다시 그려, 저장된 선택이 복원되는지 본다.
    render(
      <I18nProvider>
        <LanguageToggle />
      </I18nProvider>,
    )
    expect(screen.getByRole('button', { name: 'EN' }).getAttribute('aria-pressed')).toBe('true')
  })
})
