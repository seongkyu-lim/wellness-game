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
  return (
    <section className="card" aria-label="계정">
      <div className="section-head">
        <h2 className="section-title">👤 계정</h2>
        <span className={`pill ${session ? 'lime' : 'muted'}`}>
          {session ? `${PROVIDER_LABELS[session.provider]} 로그인` : '로그인 필요'}
        </span>
      </div>

      {session ? (
        <>
          <p className="hint">
            {session.displayName ?? '회원'}님, 이 계정의 캐릭터 기록을 조회하고 있어요. 기록은 iPhone 앱에서
            같은 계정으로 로그인해 동기화하면 쌓입니다.
          </p>
          <p className="mono">{session.userId}</p>
          <button className="btn btn-secondary" onClick={onLogout}>
            로그아웃
          </button>
        </>
      ) : (
        <>
          <p className="hint">
            내 캐릭터와 활동 기록을 보려면 iPhone 앱과 같은 계정으로 로그인해 주세요. 로그인한 본인의 데이터만 볼 수
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
        </>
      )}
    </section>
  )
}
