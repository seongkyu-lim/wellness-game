import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from './i18n/I18nProvider'
import { legalDocumentFor, PRIVACY_CONTACT_EMAIL } from './legal/legal'
import Root from './Root'

function renderAt(path: string) {
  window.history.replaceState(null, '', path)
  return render(
    <I18nProvider>
      <Root />
    </I18nProvider>,
  )
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.setItem('wellness.lang', 'ko')
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

describe('legalDocumentFor', () => {
  it('/privacy·/terms(끝 슬래시 허용)만 법적 고지 페이지로 본다', () => {
    expect(legalDocumentFor('/privacy')).toBe('privacy')
    expect(legalDocumentFor('/terms/')).toBe('terms')
    expect(legalDocumentFor('/')).toBeNull()
    expect(legalDocumentFor('/privacy/extra')).toBeNull()
  })
})

describe('법적 고지 라우트', () => {
  it('/privacy는 로그인 없이 개인정보 처리방침을 그리고 API를 호출하지 않는다', () => {
    renderAt('/privacy')
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('개인정보 처리방침')
    // 수집 항목·건강 데이터 광고 미사용·소셜 로그인 제공자·연락처
    expect(screen.getByRole('heading', { name: '1. 서버에 저장하는 정보' })).toBeTruthy()
    expect(document.body.textContent).toContain('HealthKit')
    expect(document.body.textContent).toContain('Health Connect')
    expect(document.body.textContent).toContain('광고·마케팅')
    expect(document.body.textContent).toContain('구글·카카오·네이버·Apple')
    expect(document.body.textContent).toContain(PRIVACY_CONTACT_EMAIL)
    // 심박수는 기기 안에서만 쓰고 서버에 보내지 않음, IP는 저장하지 않음, 비밀번호·리프레시 토큰은 해시로만
    expect(screen.getByRole('heading', { name: '2. 저장하지 않는 정보' })).toBeTruthy()
    expect(document.body.textContent).toContain('심박수는 기기 안에서 Watch 운동 화면 표시에만 사용하며 서버로 전송하거나 저장하지 않습니다')
    expect(document.body.textContent).toContain('bcrypt 해시')
    expect(document.body.textContent).toContain('7일')
    expect(document.body.textContent).toContain('앱 안의 계정 삭제 기능')
    expect(document.body.textContent).not.toContain('{email}')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('/terms는 이용약관을 그리고 언어를 바꾸면 영어로 다시 그린다', () => {
    renderAt('/terms')
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('이용약관')
    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Terms of Service')
    expect(screen.getByRole('heading', { name: '3. Not a medical service' })).toBeTruthy()
  })

  it('목록 줄은 ul로 묶어 그린다', () => {
    renderAt('/privacy')
    const lists = document.querySelectorAll('article ul')
    expect(lists.length).toBeGreaterThan(0)
    expect(lists[0].querySelectorAll('li').length).toBe(5)
  })

  it('처리 위탁·국외 이전은 받는 곳·국가·목적·항목 표로 그린다', () => {
    renderAt('/privacy')
    const table = screen.getByRole('table')
    const headers = Array.from(table.querySelectorAll('th')).map((th) => th.textContent)
    expect(headers).toEqual(['받는 곳', '국가', '목적', '항목'])
    const rows = Array.from(table.querySelectorAll('tbody tr')).map((tr) => Array.from(tr.children).map((td) => td.textContent))
    expect(rows.map((r) => [r[0], r[1]])).toEqual([
      ['Vercel', '미국'],
      ['Render', '싱가포르'],
      ['Supabase', '싱가포르'],
      ['소셜 로그인 제공자(구글·Apple / 카카오·네이버)', '미국 / 대한민국'],
    ])
    act(() => screen.getByRole('button', { name: 'EN' }).click())
    expect(Array.from(screen.getByRole('table').querySelectorAll('th')).map((th) => th.textContent)).toEqual([
      'Recipient',
      'Country',
      'Purpose',
      'Data',
    ])
  })

  it('메인 화면과 법적 고지 페이지 모두 푸터에 두 페이지 링크가 있다', () => {
    for (const path of ['/', '/privacy']) {
      const { unmount } = renderAt(path)
      const nav = screen.getByRole('navigation', { name: '사이트 정보' })
      const links = Array.from(nav.querySelectorAll('a')).map((a) => a.getAttribute('href'))
      expect(links).toEqual(['/privacy', '/terms'])
      unmount()
    }
  })
})
