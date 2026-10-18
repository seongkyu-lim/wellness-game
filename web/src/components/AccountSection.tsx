import { useI18n } from '../i18n/I18nProvider'
import { getLoginProviderLayout } from '../lib/loginProviders'
import { isConfigured, startLogin, type Provider, type Session } from '../lib/auth'

const PROVIDER_LABELS: Record<Provider, string> = {
  google: 'Google',
  kakao: 'Kakao',
  naver: 'Naver',
}

const PROVIDER_CLASSES: Record<Provider, string> = {
  google: 'btn',
  kakao: 'btn btn-kakao',
  naver: 'btn btn-naver',
}

interface Props {
  session: Session | null
  onLogout: () => void
}

/** 로그인 안내(세션 없음) 또는 현재 로그인 계정 정보(세션 있음)를 보여준다. */
export function AccountSection({ session, onLogout }: Props) {
  const { t, lang } = useI18n()
  const layout = getLoginProviderLayout(lang)
  const renderButton = (provider: Provider) => (
    <button
      key={provider}
      className={PROVIDER_CLASSES[provider]}
      onClick={() => startLogin(provider)}
      disabled={!isConfigured(provider)}
    >
      {t('account.signInWith', { provider: PROVIDER_LABELS[provider] })}
    </button>
  )
  return (
    <section className="card" aria-label={t('account.label')}>
      <div className="section-head">
        <h2 className="section-title">{t('account.title')}</h2>
        <span className={`chip ${session ? 'green' : 'muted'}`}>
          {session
            ? t('account.signedIn', { provider: PROVIDER_LABELS[session.provider] })
            : t('account.signInRequired')}
        </span>
      </div>

      {session ? (
        <>
          <p className="hint">
            {t('account.signedInHint', { name: session.displayName ?? t('account.defaultName') })}
          </p>
          <p className="mono">{session.userId}</p>
          <button className="btn" onClick={onLogout}>
            {t('account.logout')}
          </button>
        </>
      ) : (
        <>
          <p className="hint">
            {t('account.signInHint')}
          </p>
          <div className="social-btns">
            {layout.primary.map(renderButton)}
          </div>
          {layout.secondary.length > 0 && (
            <details className="social-more">
              <summary>{t('account.otherSignIn')}</summary>
              <div className="social-btns">{layout.secondary.map(renderButton)}</div>
            </details>
          )}
          {(['google', 'kakao', 'naver'] as Provider[]).some((p) => !isConfigured(p)) && (
            <p className="hint">{t('account.disabledHint')}</p>
          )}
        </>
      )}
    </section>
  )
}
