import type { Character } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { growthStage, nextStage } from '../lib/growthStage'
import { CharacterAvatar } from './CharacterAvatar'

const RADIUS = 45
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** 진행률 0이어도 살짝 보이도록 최소 호를 유지하고 1을 넘지 않게 자른다. (iOS XPRingView와 동일) */
export function ringFraction(progress: number): number {
  return Math.max(0.015, Math.min(progress, 1))
}

export function xpProgress(character: Character): number {
  if (character.nextLevelXp <= 0) {
    return 0
  }
  return Math.min(Math.max(character.currentXp / character.nextLevelXp, 0), 1)
}

const STATS = [
  { key: 'str', title: 'STR', color: 'var(--stat-str)' },
  { key: 'vit', title: 'VIT', color: 'var(--stat-vit)' },
  { key: 'discipline', title: 'DISC', color: 'var(--stat-disc)' },
  { key: 'recovery', title: 'REC', color: 'var(--stat-rec)' },
] as const

interface Props {
  character: Character | null
}

export function CharacterCard({ character }: Props) {
  const { t, formatNumber } = useI18n()
  if (!character) {
    return (
      <section className="hero-card">
        <div className="empty-state">
          <CharacterAvatar level={1} size={64} />
          <div>
            <h2>{t('character.emptyTitle')}</h2>
            <p>{t('character.emptyBody')}</p>
          </div>
        </div>
      </section>
    )
  }

  const fraction = ringFraction(xpProgress(character))
  const stage = growthStage(character.level)
  const next = nextStage(character.level)

  return (
    <section className="hero-card" aria-label={t('character.label')}>
      <div className="hero-row">
        <div className="ring-wrap">
          <svg width="110" height="110" viewBox="0 0 110 110" aria-hidden>
            <defs>
              <linearGradient id="xp-gradient" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stopColor="#9bd65c" />
                <stop offset="100%" stopColor="#5ca843" />
              </linearGradient>
            </defs>
            <circle cx="55" cy="55" r={RADIUS} fill="none" stroke="var(--ring-track)" strokeWidth="9" />
            <circle
              cx="55"
              cy="55"
              r={RADIUS}
              fill="none"
              stroke="url(#xp-gradient)"
              strokeWidth="9"
              strokeLinecap="round"
              strokeDasharray={CIRCUMFERENCE}
              strokeDashoffset={CIRCUMFERENCE * (1 - fraction)}
              transform="rotate(-90 55 55)"
              style={{ transition: 'stroke-dashoffset 0.8s ease-out' }}
            />
          </svg>
          <div className="ring-center">
            <CharacterAvatar level={character.level} size={72} />
          </div>
        </div>
        <div className="hero-meta">
          <span className="pill lime">
            {t('character.stageBadge', { level: character.level, stage: t(`stage.${stage.key}`) })}
          </span>
          <span className="xp">
            {formatNumber(character.currentXp)} / {formatNumber(character.nextLevelXp)} XP
          </span>
          <span className="hint">
            {next
              ? t('character.nextStage', { level: next.minLevel, stage: t(`stage.${next.key}`) })
              : t('character.maxStage')}
          </span>
        </div>
      </div>
      <div className="stat-grid">
        {STATS.map((stat) => (
          <div className="stat-tile" key={stat.key}>
            <div className="value" style={{ color: stat.color }}>
              {character.stats[stat.key]}
            </div>
            <div className="name">
              {stat.title} · {t(`stat.${stat.key}`)}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
