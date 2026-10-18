import type { CSSProperties, ReactNode } from 'react'

// 굵은 선 아이콘 — 이모지 대신 사용한다. 색은 currentColor를 따른다.
interface IconProps {
  size?: number
}

function StrokeIcon({ size = 22, children }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {children}
    </svg>
  )
}

export const IconStrength = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M6 7v10M18 7v10M3 10v4M21 10v4M6 12h12" />
  </StrokeIcon>
)

export const IconHeart = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />
  </StrokeIcon>
)

export const IconTarget = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <circle cx="12" cy="12" r="8" />
    <circle cx="12" cy="12" r="4" />
  </StrokeIcon>
)

export const IconMoon = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" />
  </StrokeIcon>
)

export const IconWalk = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <circle cx="13" cy="4" r="2" />
    <path d="M9 21l2-6 3 3v3M7 12l3-4 4 1 3 4M11 15l-1-4" />
  </StrokeIcon>
)

export const IconRun = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <circle cx="15" cy="4" r="2" />
    <path d="M5 21l4-5 3 2 1-5 4 3 2-1M8 10l3-3 4 2" />
  </StrokeIcon>
)

export const IconWave = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M3 15c2 0 2-2 4.5-2s2.5 2 4.5 2 2-2 4.5-2 2.5 2 4.5 2M3 20c2 0 2-2 4.5-2s2.5 2 4.5 2 2-2 4.5-2 2.5 2 4.5 2" />
    <circle cx="15" cy="6" r="2" />
  </StrokeIcon>
)

export const IconBike = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <circle cx="6" cy="16" r="4" />
    <circle cx="18" cy="16" r="4" />
    <path d="M6 16l4-8h5l3 8M10 8l2 8" />
  </StrokeIcon>
)

export const IconPhone = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <rect x="6" y="2" width="12" height="20" rx="3" />
    <path d="M11 18h2" />
  </StrokeIcon>
)

export const IconSprout = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M12 21v-9M12 12c0-4-3-6-7-6 0 4 3 6 7 6zM12 10c0-4 3-6 7-6 0 4-3 6-7 6z" />
  </StrokeIcon>
)

export const IconLock = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <rect x="5" y="11" width="14" height="10" rx="2" />
    <path d="M8 11V8a4 4 0 0 1 8 0v3" />
  </StrokeIcon>
)

export const IconChart = ({ size }: IconProps) => (
  <StrokeIcon size={size}>
    <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
  </StrokeIcon>
)

const SPARKLE_PATH = 'M12 0 L14.5 9.5 L24 12 L14.5 14.5 L12 24 L9.5 14.5 L0 12 L9.5 9.5Z'

interface SparkleProps {
  size: number
  fill: string
  timing?: 'slow' | 'late'
  style: CSSProperties
}

/** 반짝이는 네 갈래 별 장식. */
export function Sparkle({ size, fill, timing, style }: SparkleProps) {
  return (
    <svg
      className={`sparkle twinkle ${timing ?? ''}`}
      style={style}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden
    >
      <path d={SPARKLE_PATH} fill={fill} strokeWidth="1.8" />
    </svg>
  )
}
