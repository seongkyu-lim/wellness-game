import type { ReactNode } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import { LEGAL_DOCUMENTS, LEGAL_EFFECTIVE_DATE, PRIVACY_CONTACT_EMAIL, pagePath, type LegalDocument } from '../legal/legal'
import { LanguageToggle } from './LanguageToggle'
import { SiteFooter } from './SiteFooter'

/** 사전 문구를 문단으로 그린다. 줄 단위로 나누고 '- '로 시작하는 줄이 이어지면 목록으로 묶는다. */
function renderBody(text: string): ReactNode[] {
  const blocks: ReactNode[] = []
  let items: string[] = []
  const flush = () => {
    if (items.length > 0) {
      blocks.push(
        <ul key={blocks.length}>
          {items.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>,
      )
      items = []
    }
  }
  for (const line of text.split('\n')) {
    if (line.startsWith('- ')) {
      items.push(line.slice(2))
    } else {
      flush()
      blocks.push(<p key={blocks.length}>{line}</p>)
    }
  }
  flush()
  return blocks
}

/** 로그인 없이 볼 수 있는 개인정보 처리방침·이용약관 페이지. */
export function LegalPage({ document }: { document: LegalDocument }) {
  const { t } = useI18n()
  const doc = LEGAL_DOCUMENTS[document]
  const params = { email: PRIVACY_CONTACT_EMAIL }

  return (
    <main className="container">
      <header className="header">
        <div className="header-top">
          <div>
            <a className="brand-link" href={pagePath('')} aria-label={t('legal.home')}>
              <p className="brand">
                Wellness <span>Game</span>
              </p>
            </a>
          </div>
          <LanguageToggle />
        </div>
      </header>

      <article className="card legal">
        <h1>{t(doc.title)}</h1>
        <p className="hint">{t('legal.effectiveDate', { date: LEGAL_EFFECTIVE_DATE })}</p>
        {renderBody(t(doc.intro, params))}
        {doc.sections.map((section) => (
          <section key={section.title}>
            <h2>{t(section.title)}</h2>
            {renderBody(t(section.body, params))}
          </section>
        ))}
      </article>

      <SiteFooter />
    </main>
  )
}
