import { useI18n } from '../i18n/I18nProvider'
import { CharacterAvatar } from './CharacterAvatar'
import { Sparkle } from './Icons'

interface Props {
  level: number
  bubble: string
  /** 이름표 오른쪽 레벨 칩 문구 */
  badge: string
  label: string
  /**
   * true면 장면 전체를 한 장의 그림(role="img")으로 읽히게 하고 안쪽 글자는 숨긴다.
   * 소개용 장식 장면(랜딩)에 쓴다. false면 말풍선·칩 글자를 그대로 읽는다(대시보드).
   */
  asImage?: boolean
}

/** 하늘 패널 + 언덕 + 말풍선 + 반짝이 + 흔들리는 새싹이 + 이름표. 대시보드와 랜딩이 함께 쓴다. */
export function HeroScene({ level, bubble, badge, label, asImage = false }: Props) {
  const { t } = useI18n()
  const hideText = asImage || undefined
  return (
    <section className="hero" role={asImage ? 'img' : undefined} aria-label={label}>
      <svg className="hills" viewBox="0 0 390 110" preserveAspectRatio="none" aria-hidden>
        <path className="hill" d="M0 52 Q70 24 150 46 T300 40 T390 50 V110 H0Z" />
        <path className="hill-deep" d="M0 74 Q90 58 190 72 T390 70 V110 H0Z" />
      </svg>
      <p className="bubble" aria-hidden={hideText}>
        {bubble}
      </p>
      <Sparkle size={26} fill="#ffe066" style={{ right: 34, top: 26 }} />
      <Sparkle size={16} fill="#ffffff" timing="slow" style={{ right: 78, top: 84 }} />
      <Sparkle size={18} fill="#ff9ec4" timing="late" style={{ left: 48, top: 150 }} />
      <div className="mascot bob">
        <CharacterAvatar level={level} size={210} decorative={asImage} />
      </div>
      <div className="nameplate" aria-hidden={hideText}>
        <span className="name-tag">{t('character.name')}</span>
        <span className="level-chip">{badge}</span>
      </div>
    </section>
  )
}
