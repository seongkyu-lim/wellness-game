import { useI18n } from '../i18n/I18nProvider'
import { isConfigured, startLogin, type Provider, type Session } from '../lib/auth'

const PROVIDER_LABELS: Record<Provider, string> = {
  google: 'Google',
  kakao: 'Kakao',
  naver: 'Naver',
}

interface Props {
  session: Session | null
  onLogout: () => void
}

/** 로그인 안내(세션 없음) 또는 현재 로그인 계정 정보(세션 있음)를 보여준다. */
export function AccountSection({ session, onLogout }: Props) {
  const { t } = useI18n()
  return (
    <section className="card" aria-label={t('account.label')}>
      <div className="section-head">
        <h2 className="section-title">{t('account.title')}</h2>
        <span className={`pill ${session ? 'lime' : 'muted'}`}>
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
          <button className="btn btn-secondary" onClick={onLogout}>
            {t('account.logout')}
          </button>
        </>
      ) : (
        <>
          <p className="hint">
            {t('account.signInHint')}
          </p>
          <div className="social-btns">
            <button
              className="btn btn-google"
              onClick={() => startLogin('google')}
              disabled={!isConfigured('google')}
            >
              <b>G</b> {t('account.signInWith', { provider: 'Google' })}
            </button>
            <button className="btn btn-kakao" onClick={() => startLogin('kakao')} disabled={!isConfigured('kakao')}>
              {t('account.signInWith', { provider: 'Kakao' })}
            </button>
            <button className="btn btn-naver" onClick={() => startLogin('naver')} disabled={!isConfigured('naver')}>
              <b>N</b> {t('account.signInWith', { provider: 'Naver' })}
            </button>
          </div>
          {(['google', 'kakao', 'naver'] as Provider[]).some((p) => !isConfigured(p)) && (
            <p className="hint">{t('account.disabledHint')}</p>
          )}
        </>
      )}
    </section>
  )
}
