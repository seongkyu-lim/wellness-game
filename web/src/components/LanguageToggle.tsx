import { useI18n } from '../i18n/I18nProvider'
import type { Language } from '../i18n/core'

const OPTIONS: { lang: Language; label: string }[] = [
  { lang: 'ko', label: '한' },
  { lang: 'en', label: 'EN' },
]

/** ko/EN 수동 전환. 선택은 localStorage에 저장된다. */
export function LanguageToggle() {
  const { lang, setLang, t } = useI18n()
  return (
    <div className="lang-toggle" role="group" aria-label={t('lang.toggle')}>
      {OPTIONS.map((o) => (
        <button
          key={o.lang}
          type="button"
          className={`lang-btn${lang === o.lang ? ' active' : ''}`}
          aria-pressed={lang === o.lang}
          lang={o.lang}
          title={t(o.lang === 'ko' ? 'lang.ko' : 'lang.en')}
          onClick={() => setLang(o.lang)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
