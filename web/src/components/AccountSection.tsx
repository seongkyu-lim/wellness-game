import { useState } from 'react'
import { isConfigured, startLogin, type Provider, type Session } from '../lib/auth'

const PROVIDER_LABELS: Record<Provider, string> = {
  google: 'Google',
  kakao: 'Kakao',
  naver: 'Naver',
}

interface Props {
  session: Session | null
  userId: string
  onLogout: () => void
  onApplyManualId: (userId: string) => void
}

export function AccountSection({ session, userId, onLogout, onApplyManualId }: Props) {
  const [draft, setDraft] = useState('')

  return (
    <section className="card" aria-label="계정">
      <div className="section-head">
        <h2 className="section-title">👤 계정</h2>
        <span className={`pill ${session ? 'lime' : 'muted'}`}>
          {session ? `${PROVIDER_LABELS[session.provider]} 로그인` : '게스트'}
        </span>
      </div>

      {session ? (
        <>
          <p className="hint">
            {session.displayName ?? '회원'}님, iPhone 앱에서도 {PROVIDER_LABELS[session.provider]} 계정으로 로그인하면
            같은 캐릭터가 자동으로 이어집니다.
          </p>
          <p className="mono">{userId}</p>
          <button className="btn btn-secondary" onClick={onLogout}>
            로그아웃
          </button>
        </>
      ) : (
        <>
          <p className="hint">
            iPhone 앱과 같은 계정으로 로그인하면 캐릭터가 자동으로 연동됩니다. 로그인하지 않아도 게스트로 이용할 수
            있어요.
          </p>
          <div className="social-btns">
            <button
              className="btn btn-google"
              onClick={() => startLogin('google')}
              disabled={!isConfigured('google')}
            >
              <b>G</b> Google로 로그인
            </button>
            <button className="btn btn-kakao" onClick={() => startLogin('kakao')} disabled={!isConfigured('kakao')}>
              Kakao로 로그인
            </button>
            <button className="btn btn-naver" onClick={() => startLogin('naver')} disabled={!isConfigured('naver')}>
              <b>N</b> Naver로 로그인
            </button>
          </div>
          {(['google', 'kakao', 'naver'] as Provider[]).some((p) => !isConfigured(p)) && (
            <p className="hint">
              비활성화된 버튼은 provider 키가 설정되지 않은 것입니다. 설정 방법은 web/README.md를 참고하세요. (Apple
              로그인은 Apple Developer 유료 계정과 HTTPS 도메인이 필요해 웹에서는 아직 지원하지 않아요.)
            </p>
          )}
          <p className="mono">{userId}</p>
          <div className="userid-row">
            <input
              placeholder="개발용: 사용자 ID 직접 입력 (예: guest:...)"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <button
              className="btn btn-secondary"
              style={{ width: 'auto' }}
              onClick={() => {
                onApplyManualId(draft.trim())
                setDraft('')
              }}
              disabled={!draft.trim()}
            >
              적용
            </button>
          </div>
        </>
      )}
    </section>
  )
}
