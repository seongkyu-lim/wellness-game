import { act, render, renderHook, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { ErrorBanner } from '../components/ErrorBanner'
import { LanguageToggle } from '../components/LanguageToggle'
import { getLanguage, type Message } from './core'
import { I18nProvider, useI18n } from './I18nProvider'

const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>

describe('I18nProvider Intl 포맷', () => {
  beforeEach(() => localStorage.clear())

  it('ko는 ko-KR, en은 en-US 형식으로 숫자·날짜를 포맷한다', () => {
    localStorage.setItem('wellness.lang', 'ko')
    const { result } = renderHook(() => useI18n(), { wrapper })
    const date = new Date(2026, 2, 5) // 2026-03-05 (로컬)
    expect(result.current.formatNumber(1234567)).toBe('1,234,567')
    expect(result.current.formatDate(date, { month: 'long', day: 'numeric' })).toBe('3월 5일')

    act(() => result.current.setLang('en'))
    expect(result.current.formatDate(date, { month: 'long', day: 'numeric' })).toBe('March 5')
    expect(result.current.formatNumber(1234.5)).toBe('1,234.5')
  })

  it('언어를 바꾸면 저장하고 <html lang>과 API용 전역 언어를 맞춘다', () => {
    localStorage.setItem('wellness.lang', 'ko')
    const { result } = renderHook(() => useI18n(), { wrapper })
    act(() => result.current.setLang('en'))
    expect(localStorage.getItem('wellness.lang')).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(getLanguage()).toBe('en')
  })
})

describe('ErrorBanner 재번역', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('wellness.lang', 'ko')
  })

  function renderBanner(message: Message | null) {
    return render(
      <I18nProvider>
        <LanguageToggle />
        <ErrorBanner message={message} />
      </I18nProvider>,
    )
  }

  it('이미 떠 있는 키 메시지 배너를 언어 전환 시 다시 번역한다', () => {
    renderBanner({ key: 'error.request', params: { status: 502 } })
    expect(screen.getByRole('alert').textContent).toBe('요청에 실패했습니다. (502)')

    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(screen.getByRole('alert').textContent).toBe('Request failed (502).')

    act(() => screen.getByRole('button', { name: '한' }).click())
    expect(screen.getByRole('alert').textContent).toBe('요청에 실패했습니다. (502)')
  })

  it('서버 원문(raw)은 언어를 바꿔도 그대로 표시한다', () => {
    renderBanner({ raw: '캐릭터가 없습니다.' })
    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(screen.getByRole('alert').textContent).toBe('캐릭터가 없습니다.')
  })

  it('메시지가 없으면 그리지 않는다', () => {
    renderBanner(null)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
