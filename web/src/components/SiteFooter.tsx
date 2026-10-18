import { useI18n } from '../i18n/I18nProvider'
import { pagePath } from '../legal/legal'

/** 모든 페이지 하단의 개인정보 처리방침·이용약관 링크. */
export function SiteFooter() {
  const { t } = useI18n()
  return (
    <footer className="site-footer">
      <nav aria-label={t('footer.label')}>
        <a href={pagePath('privacy')}>{t('footer.privacy')}</a>
        <span aria-hidden> · </span>
        <a href={pagePath('terms')}>{t('footer.terms')}</a>
      </nav>
    </footer>
  )
}
