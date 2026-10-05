import type { ReactNode } from 'react'
import type { Character } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { growthStage, nextStage } from '../lib/growthStage'
import { CharacterAvatar } from './CharacterAvatar'
import { IconHeart, IconMoon, IconStrength, IconTarget, Sparkle } from './Icons'

export function xpProgress(character: Character): number {
  if (character.nextLevelXp <= 0) {
    return 0
  }
  return Math.min(Math.max(character.currentXp / character.nextLevelXp, 0), 1)
}

const STATS: { key: 'str' | 'vit' | 'discipline' | 'recovery'; title: string; color: string; icon: ReactNode }[] = [
  { key: 'str', title: 'STR', color: 'var(--stat-str)', icon: <IconStrength /> },
  { key: 'vit', title: 'VIT', color: 'var(--stat-vit)', icon: <IconHeart /> },
  { key: 'discipline', title: 'DISC', color: 'var(--stat-disc)', icon: <IconTarget /> },
  { key: 'recovery', title: 'REC', color: 'var(--stat-rec)', icon: <IconMoon /> },
]

interface Props {
  character: Character | null
}

export function CharacterCard({ character }: Props) {
  const { t } = useI18n()
  const level = character?.level ?? 1
  const stage = growthStage(level)
  const next = nextStage(level)

  let bubble = t('character.bubbleEmpty')
  if (character) {
    bubble = next
      ? t('character.bubbleNext', { level: next.minLevel, stage: t(`stage.${next.key}`) })
      : t('character.bubbleMax')
  }

  return (
    <>
      <section className="hero" aria-label={t('character.label')}>
        <svg className="hills" viewBox="0 0 390 110" preserveAspectRatio="none" aria-hidden>
          <path className="hill" d="M0 52 Q70 24 150 46 T300 40 T390 50 V110 H0Z" />
          <path className="hill-deep" d="M0 74 Q90 58 190 72 T390 70 V110 H0Z" />
        </svg>
        <p className="bubble">{bubble}</p>
        <Sparkle size={26} fill="#ffe066" style={{ right: 34, top: 26 }} />
        <Sparkle size={16} fill="#ffffff" timing="slow" style={{ right: 78, top: 84 }} />
        <Sparkle size={18} fill="#ff9ec4" timing="late" style={{ left: 48, top: 150 }} />
        <div className="mascot bob">
          <CharacterAvatar level={level} size={210} />
        </div>
        <div className="nameplate">
          <span className="name-tag">{t('character.name')}</span>
          <span className="level-chip">{character ? t('character.stageBadge', { level, stage: t(`stage.${stage.key}`) }) : t('character.notSynced')}</span>
        </div>
      </section>

      {character && <XpCard character={character} nextLabel={
            next
              ? t('character.nextStageLabel', { stage: t(`stage.${next.key}`), level: next.minLevel })
              : t('character.finalStage')
          } />}

      {character && (
        <section className="stat-grid" aria-label={t('character.statsLabel')}>
          {STATS.map((stat) => (
            <div className="stat-tile" key={stat.key}>
              <div className="stat-band" style={{ background: stat.color }}>
                {stat.icon}
              </div>
              <span className="stat-value">{character.stats[stat.key]}</span>
              <span className="stat-name">
                {stat.title} {t(`stat.${stat.key}`)}
              </span>
            </div>
          ))}
        </section>
      )}
    </>
  )
}

function XpCard({ character, nextLabel }: { character: Character; nextLabel: string }) {
  const { t, formatNumber } = useI18n()
  const percent = xpProgress(character) * 100
  return (
    <section className="card xp-card" aria-label={t('character.xpLabel')}>
      <div className="xp-head">
        <span className="xp-label">EXP</span>
        <span className="chip pink">{t('character.xpTotal', { xp: formatNumber(character.totalXp) })}</span>
      </div>
      <div
        className="xp-bar"
        role="progressbar"
        aria-label={t('character.xpBarLabel')}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(percent)}
        aria-valuetext={`${formatNumber(character.currentXp)} / ${formatNumber(character.nextLevelXp)} XP`}
      >
        <div className="xp-fill" style={{ width: `${percent}%` }} />
      </div>
      <div className="xp-foot">
        <span>
          {formatNumber(character.currentXp)} / {formatNumber(character.nextLevelXp)} XP
        </span>
        <span className="hint">{nextLabel}</span>
      </div>
    </section>
  )
}
