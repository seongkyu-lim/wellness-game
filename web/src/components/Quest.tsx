import type { ReactNode } from 'react'

interface Props {
  icon: ReactNode
  tint: string
  title: string
  sub: string
  xp?: number
}

/** 틴트 아이콘 상자 + 제목/부제 + 오른쪽 '+N XP' 칩. 활동 기록과 랜딩 스탯 소개가 함께 쓴다. */
export function Quest({ icon, tint, title, sub, xp }: Props) {
  return (
    <div className="quest">
      <div className="quest-icon" style={{ background: tint }}>
        {icon}
      </div>
      <div className="quest-body">
        <span className="quest-title">{title}</span>
        <span className="quest-sub">{sub}</span>
      </div>
      {xp != null && xp > 0 && <span className="chip">+{xp} XP</span>}
    </div>
  )
}
