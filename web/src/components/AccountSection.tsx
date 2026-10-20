import { useState } from 'react'
import { toMessage, type Message } from '../i18n/core'
import { useI18n } from '../i18n/I18nProvider'
import { ErrorBanner } from './ErrorBanner'
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
  // 로그인 시작(PKCE 준비) 중에는 버튼을 막아 중복 시작을 피한다. 성공하면 페이지를 떠나므로 되돌리지 않는다.
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<Message | null>(null)
  const handleLogin = (provider: Provider) => {
    setStarting(true)
    setStartError(null)
    startLogin(provider).catch((e: unknown) => {
      // 예: 보안 연결이 아니라 crypto.subtle을 쓸 수 없는 경우
      setStartError(toMessage(e, 'error.loginStartFailed'))
      setStarting(false)
    })
  }
  const renderButton = (provider: Provider) => (
    <button
      key={provider}
      className={PROVIDER_CLASSES[provider]}
      onClick={() => handleLogin(provider)}
      disabled={starting || !isConfigured(provider)}
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
          <ErrorBanner message={startError} />
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
