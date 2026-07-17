import type { Character } from '../api/types'

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
  { key: 'str', title: 'STR', name: '근력', color: 'var(--stat-str)' },
  { key: 'vit', title: 'VIT', name: '활력', color: 'var(--stat-vit)' },
  { key: 'discipline', title: 'DISC', name: '절제', color: 'var(--stat-disc)' },
  { key: 'recovery', title: 'REC', name: '회복', color: 'var(--stat-rec)' },
] as const

interface Props {
  character: Character | null
}

export function CharacterCard({ character }: Props) {
  if (!character) {
    return (
      <section className="hero-card">
        <div className="empty-state">
          <span className="emoji" aria-hidden>
            🌱
          </span>
          <div>
            <h2>캐릭터가 기다리고 있어요</h2>
            <p>활동을 기록하고 동기화하면 XP를 얻고 캐릭터가 성장해요.</p>
          </div>
        </div>
      </section>
    )
  }

  const fraction = ringFraction(xpProgress(character))

  return (
    <section className="hero-card" aria-label="내 캐릭터">
      <div className="hero-row">
        <svg width="110" height="110" viewBox="0 0 110 110" role="img" aria-label={`레벨 ${character.level}`}>
          <defs>
            <linearGradient id="xp-gradient" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stopColor="#9bd65c" />
              <stop offset="100%" stopColor="#5ca843" />
            </linearGradient>
          </defs>
          <circle cx="55" cy="55" r={RADIUS} fill="none" stroke="var(--ring-track)" strokeWidth="11" />
          <circle
            cx="55"
            cy="55"
            r={RADIUS}
            fill="none"
            stroke="url(#xp-gradient)"
            strokeWidth="11"
            strokeLinecap="round"
            strokeDasharray={CIRCUMFERENCE}
            strokeDashoffset={CIRCUMFERENCE * (1 - fraction)}
            transform="rotate(-90 55 55)"
            style={{ transition: 'stroke-dashoffset 0.8s ease-out' }}
          />
          <text x="55" y="50" textAnchor="middle" fontSize="12" fill="var(--text-secondary)" fontWeight="600">
            Lv.
          </text>
          <text x="55" y="74" textAnchor="middle" fontSize="26" className="ring-level">
            {character.level}
          </text>
        </svg>
        <div className="hero-meta">
          <span className="label">내 캐릭터</span>
          <span className="xp">
            {character.currentXp.toLocaleString()} / {character.nextLevelXp.toLocaleString()} XP
          </span>
          <span className="pill lime">총 {character.totalXp.toLocaleString()} XP</span>
        </div>
      </div>
      <div className="stat-grid">
        {STATS.map((stat) => (
          <div className="stat-tile" key={stat.key}>
            <div className="value" style={{ color: stat.color }}>
              {character.stats[stat.key]}
            </div>
            <div className="name">
              {stat.title} · {stat.name}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
