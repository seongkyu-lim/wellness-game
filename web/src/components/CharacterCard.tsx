import type { ReactNode } from 'react'
import type { Character } from '../api/types'
import { growthStage, nextStage } from '../lib/growthStage'
import { CharacterAvatar } from './CharacterAvatar'
import { IconHeart, IconMoon, IconStrength, IconTarget, Sparkle } from './Icons'

export function xpProgress(character: Character): number {
  if (character.nextLevelXp <= 0) {
    return 0
  }
  return Math.min(Math.max(character.currentXp / character.nextLevelXp, 0), 1)
}

const STATS: { key: 'str' | 'vit' | 'discipline' | 'recovery'; title: string; name: string; color: string; icon: ReactNode }[] = [
  { key: 'str', title: 'STR', name: '근력', color: 'var(--stat-str)', icon: <IconStrength /> },
  { key: 'vit', title: 'VIT', name: '활력', color: 'var(--stat-vit)', icon: <IconHeart /> },
  { key: 'discipline', title: 'DISC', name: '절제', color: 'var(--stat-disc)', icon: <IconTarget /> },
  { key: 'recovery', title: 'REC', name: '회복', color: 'var(--stat-rec)', icon: <IconMoon /> },
]

interface Props {
  character: Character | null
}

export function CharacterCard({ character }: Props) {
  const level = character?.level ?? 1
  const stage = growthStage(level)
  const next = nextStage(level)

  let bubble = 'iPhone 앱에서 동기화하면 나도 깨어날게!'
  if (character) {
    bubble = next ? `Lv.${next.minLevel}이 되면 ${next.name} 단계로 자랄 거야!` : '활짝 피었어! 늘 함께해 줘서 고마워!'
  }

  return (
    <>
      <section className="hero" aria-label="내 캐릭터">
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
          <span className="name-tag">새싹이</span>
          <span className="level-chip">
            Lv.{level} · {stage.name} 단계
          </span>
        </div>
      </section>

      {character && <XpCard character={character} nextLabel={next ? `${next.name}까지 Lv.${next.minLevel}` : '최종 단계'} />}

      {character && (
        <section className="stat-grid" aria-label="스탯">
          {STATS.map((stat) => (
            <div className="stat-tile" key={stat.key}>
              <div className="stat-band" style={{ background: stat.color }}>
                {stat.icon}
              </div>
              <span className="stat-value">{character.stats[stat.key]}</span>
              <span className="stat-name">
                {stat.title} {stat.name}
              </span>
            </div>
          ))}
        </section>
      )}
    </>
  )
}

function XpCard({ character, nextLabel }: { character: Character; nextLabel: string }) {
  const percent = xpProgress(character) * 100
  return (
    <section className="card xp-card" aria-label="경험치">
      <div className="xp-head">
        <span className="xp-label">EXP</span>
        <span className="chip pink">누적 {character.totalXp.toLocaleString()} XP</span>
      </div>
      <div
        className="xp-bar"
        role="progressbar"
        aria-label="다음 레벨까지 경험치"
        aria-valuemin={0}
        aria-valuemax={character.nextLevelXp}
        aria-valuenow={character.currentXp}
      >
        <div className="xp-fill" style={{ width: `${percent}%` }} />
      </div>
      <div className="xp-foot">
        <span>
          {character.currentXp.toLocaleString()} / {character.nextLevelXp.toLocaleString()} XP
        </span>
        <span className="hint">{nextLabel}</span>
      </div>
    </section>
  )
}
