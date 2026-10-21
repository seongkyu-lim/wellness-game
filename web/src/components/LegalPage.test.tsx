import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { I18nProvider } from '../i18n/I18nProvider'
import { LEGAL_DOCUMENTS, LEGAL_EFFECTIVE_DATE, PRIVACY_CONTACT_EMAIL, type LegalDocument } from '../legal/legal'
import { LegalPage } from './LegalPage'

function renderPage(document: LegalDocument) {
  return render(
    <I18nProvider>
      <LegalPage document={document} />
    </I18nProvider>,
  )
}

const sectionHeadings = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)

beforeEach(() => localStorage.setItem('wellness.lang', 'ko'))

describe.each(['privacy', 'terms'] as const)('LegalPage(%s)', (document) => {
  it('모든 조항을 순서대로 그리고, 두 언어 모두 번호 순서와 조항 수가 같다', () => {
    renderPage(document)
    const ko = sectionHeadings()
    expect(ko).toHaveLength(LEGAL_DOCUMENTS[document].sections.length)
    act(() => screen.getByRole('button', { name: 'EN' }).click())
    const en = sectionHeadings()
    expect(en).toHaveLength(ko.length)
    // 조항 번호("1." "2." …)가 언어 사이에 어긋나지 않는다.
    const numbers = (titles: (string | null)[]) => titles.map((t) => t?.match(/^\d+\./)?.[0] ?? null)
    expect(numbers(en)).toEqual(numbers(ko))
  })

  it.each(['ko', 'en'] as const)('%s 본문에 치환되지 않은 자리표시자나 빈 문단이 없고 시행일·연락처가 들어간다', (lang) => {
    localStorage.setItem('wellness.lang', lang)
    renderPage(document)
    const article = window.document.querySelector('article')!
    expect(article.textContent).not.toMatch(/\{\w+\}/)
    expect(Array.from(article.querySelectorAll('p')).filter((p) => !p.textContent?.trim())).toEqual([])
    expect(article.textContent).toContain(LEGAL_EFFECTIVE_DATE)
    expect(article.textContent).toContain(PRIVACY_CONTACT_EMAIL)
  })

  it('표의 모든 행은 머리글과 열 수가 같고, 목록 항목에 마크업 기호(- , |)가 남지 않는다', () => {
    renderPage(document)
    for (const table of Array.from(window.document.querySelectorAll('article table'))) {
      const width = table.querySelectorAll('thead th').length
      for (const row of Array.from(table.querySelectorAll('tbody tr'))) {
        expect(row.children).toHaveLength(width)
      }
    }
    for (const li of Array.from(window.document.querySelectorAll('article li'))) {
      expect(li.textContent?.startsWith('- ')).toBe(false)
      expect(li.textContent?.startsWith('|')).toBe(false)
    }
  })
})
