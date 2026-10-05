import { useI18n } from '../i18n/I18nProvider'
import { growthStage, type StageKey } from '../lib/growthStage'

// 색상 팔레트 (라이트 그린 테마와 어울리는 고정색 — SVG 내부라 다크 모드에서도 유지)
const C = {
  potBody: '#d98e63',
  potRim: '#c97b52',
  soil: '#7a5a40',
  face: '#5b3a29',
  stem: '#4c8c46',
  leafLight: '#7cc142',
  leafDark: '#5ca843',
  trunk: '#8a6242',
  seed: '#a9805b',
  seedLine: '#8a6242',
  petal: '#f2a5c0',
  petalCore: '#e8709a',
} as const

interface Props {
  level: number
  size?: number
}

/** 레벨에 따라 씨앗 → 개화로 자라는 화분 마스코트 '새싹이'. */
export function CharacterAvatar({ level, size = 76 }: Props) {
  const { t } = useI18n()
  const stage = growthStage(level)
  return (
    <svg width={size} height={size} viewBox="0 0 120 120" role="img" aria-label={t('character.avatarLabel', { stage: t(`stage.${stage.key}`) })}>
      <Plant stage={stage.key} />
      {/* 흙과 화분 — 줄기 아래쪽을 덮도록 식물 뒤에 그린다 */}
      <ellipse cx="60" cy="75" rx="19" ry="4.5" fill={C.soil} />
      <path d="M38 79 L82 79 L77 101 Q60 106 43 101 Z" fill={C.potBody} />
      <rect x="35" y="73" width="50" height="9" rx="4.5" fill={C.potRim} />
      {/* 얼굴 */}
      <circle cx="53" cy="91" r="2.3" fill={C.face} />
      <circle cx="67" cy="91" r="2.3" fill={C.face} />
      <path d="M55 96 Q60 100 65 96" stroke={C.face} strokeWidth="2" fill="none" strokeLinecap="round" />
    </svg>
  )
}

function Plant({ stage }: { stage: StageKey }) {
  switch (stage) {
    case 'seed':
      return (
        <g>
          <ellipse cx="60" cy="66" rx="7.5" ry="9.5" fill={C.seed} />
          <path d="M60 58 Q60 62 60 70" stroke={C.seedLine} strokeWidth="1.6" fill="none" />
          <path d="M60 58 Q57 51 62 47" stroke={C.stem} strokeWidth="2.4" fill="none" strokeLinecap="round" />
        </g>
      )
    case 'sprout':
      return (
        <g>
          <path d="M60 76 Q60 64 60 56" stroke={C.stem} strokeWidth="3.5" fill="none" strokeLinecap="round" />
          <path d="M60 60 Q47 61 43 48 Q57 46 60 58 Z" fill={C.leafLight} />
          <path d="M60 60 Q73 61 77 48 Q63 46 60 58 Z" fill={C.leafDark} />
        </g>
      )
    case 'sapling':
      return (
        <g>
          <path d="M60 76 Q60 56 60 40" stroke={C.stem} strokeWidth="3.8" fill="none" strokeLinecap="round" />
          <path d="M60 64 Q48 65 44 54 Q57 52 60 62 Z" fill={C.leafLight} />
          <path d="M60 64 Q72 65 76 54 Q63 52 60 62 Z" fill={C.leafDark} />
          <path d="M60 48 Q50 49 47 40 Q58 38 60 46 Z" fill={C.leafDark} />
          <path d="M60 48 Q70 49 73 40 Q62 38 60 46 Z" fill={C.leafLight} />
        </g>
      )
    case 'young':
      return (
        <g>
          <path d="M58 76 L58 50 L62 50 L62 76 Z" fill={C.trunk} />
          <circle cx="60" cy="40" r="17" fill={C.leafDark} />
          <circle cx="49" cy="47" r="10" fill={C.leafLight} />
          <circle cx="71" cy="46" r="9" fill={C.leafLight} />
        </g>
      )
    case 'tree':
      return (
        <g>
          <path d="M57 76 L57 44 L63 44 L63 76 Z" fill={C.trunk} />
          <path d="M57 56 L48 48" stroke={C.trunk} strokeWidth="3" strokeLinecap="round" />
          <circle cx="60" cy="32" r="19" fill={C.leafDark} />
          <circle cx="44" cy="42" r="13" fill={C.leafLight} />
          <circle cx="76" cy="41" r="13" fill={C.leafLight} />
        </g>
      )
    case 'blossom':
      return (
        <g>
          <path d="M57 76 L57 44 L63 44 L63 76 Z" fill={C.trunk} />
          <path d="M57 56 L48 48" stroke={C.trunk} strokeWidth="3" strokeLinecap="round" />
          <circle cx="60" cy="32" r="19" fill={C.leafDark} />
          <circle cx="44" cy="42" r="13" fill={C.leafLight} />
          <circle cx="76" cy="41" r="13" fill={C.leafLight} />
          <Flower cx={50} cy={30} />
          <Flower cx={68} cy={24} />
          <Flower cx={78} cy={38} />
          <Flower cx={42} cy={46} />
          <Flower cx={62} cy={42} />
        </g>
      )
  }
}

function Flower({ cx, cy }: { cx: number; cy: number }) {
  return (
    <g>
      <circle cx={cx - 3} cy={cy} r="2.6" fill={C.petal} />
      <circle cx={cx + 3} cy={cy} r="2.6" fill={C.petal} />
      <circle cx={cx} cy={cy - 3} r="2.6" fill={C.petal} />
      <circle cx={cx} cy={cy + 3} r="2.6" fill={C.petal} />
      <circle cx={cx} cy={cy} r="2" fill={C.petalCore} />
    </g>
  )
}
