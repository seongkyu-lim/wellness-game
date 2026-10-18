import { useI18n } from '../i18n/I18nProvider'
import { growthStage, type StageKey } from '../lib/growthStage'

// 셀 애니메이션풍 고정색 — SVG 내부라 다크 모드에서도 유지한다. (디자인 캔버스 '성장 설정화'와 동일)
const C = {
  ink: '#1f2340',
  potBody: '#f4a06c',
  potShade: '#dc7a4c',
  potRim: '#f9bc8a',
  potShine: '#ffe2c6',
  blush: '#ff8fb1',
  mouth: '#e8577f',
  stem: '#5bb04f',
  leaf: '#8ed65a',
  leafTip: '#b4ea7a',
  canopy: '#7dcb55',
  canopyBig: '#6cc04a',
  canopyShade: '#5aa845',
  canopyBigShade: '#4e9e3e',
  canopyShine: '#bdee90',
  trunk: '#a8744a',
  seed: '#c8935e',
  petal: '#ffb3cf',
  petalCore: '#ff6fa5',
  eyeShine: '#ffffff',
} as const

type Face = 'sleep' | 'open' | 'happy'

// 단계마다 표정이 다르다: 씨앗은 잠든 얼굴, 어린나무·개화는 웃는 얼굴.
const FACE: Record<StageKey, Face> = {
  seed: 'sleep',
  sprout: 'open',
  sapling: 'open',
  young: 'happy',
  tree: 'open',
  blossom: 'happy',
}

interface Props {
  level: number
  size?: number
  /** 장식용으로 쓸 때 스크린 리더에서 숨긴다. */
  decorative?: boolean
}

/** 레벨에 따라 씨앗 → 개화로 자라는 화분 마스코트 '새싹이'. */
export function CharacterAvatar({ level, size = 76, decorative = false }: Props) {
  const { t } = useI18n()
  const stage = growthStage(level)
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 120 120"
      {...(decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': t('character.avatarLabel', { stage: t(`stage.${stage.key}`) }) })}
    >
      <ellipse cx="60" cy="112" rx="30" ry="4" fill={C.ink} opacity="0.18" />
      <g stroke={C.ink} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round">
        {/* 식물을 먼저 그려 줄기 아래쪽이 화분 테두리에 가려지게 한다 */}
        <Plant stage={stage.key} />
        <Pot />
        <FaceFor face={FACE[stage.key]} />
      </g>
    </svg>
  )
}

function Pot() {
  return (
    <>
      <path d="M38 76 H82 L77 104 Q76 109 71 109 H49 Q44 109 43 104 Z" fill={C.potBody} />
      <path d="M66 77.5 H80 L75.5 103 Q74.5 107 70 107 H63 Q68 94 66 77.5 Z" fill={C.potShade} stroke="none" />
      <rect x="33" y="68" width="54" height="12" rx="5" fill={C.potRim} />
      <rect x="38" y="71" width="20" height="3" rx="1.5" fill={C.potShine} stroke="none" />
      <ellipse cx="44" cy="97" rx="4.2" ry="2.3" fill={C.blush} stroke="none" />
      <ellipse cx="76" cy="97" rx="4.2" ry="2.3" fill={C.blush} stroke="none" />
    </>
  )
}

function FaceFor({ face }: { face: Face }) {
  switch (face) {
    case 'sleep':
      return (
        <>
          <path d="M46.5 90 Q51 93 55.5 90" fill="none" strokeWidth="2.4" />
          <path d="M64.5 90 Q69 93 73.5 90" fill="none" strokeWidth="2.4" />
          <path d="M58 98 Q60 99.5 62 98" fill="none" strokeWidth="2" />
        </>
      )
    case 'happy':
      return (
        <>
          <path d="M46.5 91 Q51 85 55.5 91" fill="none" strokeWidth="2.6" />
          <path d="M64.5 91 Q69 85 73.5 91" fill="none" strokeWidth="2.6" />
          <path d="M55.5 95.5 Q60 103 64.5 95.5 Z" fill={C.mouth} strokeWidth="2.2" />
        </>
      )
    case 'open':
      return (
        <>
          <ellipse cx="51" cy="90" rx="4.2" ry="5.6" fill={C.ink} stroke="none" />
          <ellipse cx="69" cy="90" rx="4.2" ry="5.6" fill={C.ink} stroke="none" />
          <circle cx="52.4" cy="87.8" r="1.7" fill={C.eyeShine} stroke="none" />
          <circle cx="70.4" cy="87.8" r="1.7" fill={C.eyeShine} stroke="none" />
          <circle cx="49.8" cy="92.4" r="0.8" fill={C.eyeShine} stroke="none" />
          <circle cx="67.8" cy="92.4" r="0.8" fill={C.eyeShine} stroke="none" />
          <path d="M57 97 Q60 100.5 63 97" fill="none" strokeWidth="2.2" />
        </>
      )
  }
}

function BigCanopy() {
  return (
    <>
      <path d="M52 72 L55 40 H65 L68 72 Z" fill={C.trunk} />
      <path
        d="M26 44 Q16 26 34 18 Q40 2 60 4 Q80 2 86 18 Q104 26 94 44 Q86 58 60 54 Q34 58 26 44 Z"
        fill={C.canopyBig}
      />
      <path d="M61 52.5 Q85 56 93 44 Q100 34 96 25 Q86 43 61 47 Z" fill={C.canopyBigShade} stroke="none" />
      <ellipse cx="42" cy="18" rx="8" ry="4" fill={C.canopyShine} stroke="none" />
    </>
  )
}

const FLOWERS: [number, number][] = [
  [36, 30],
  [62, 12],
  [86, 30],
  [48, 44],
  [76, 44],
  [60, 30],
]

function Plant({ stage }: { stage: StageKey }) {
  switch (stage) {
    case 'seed':
      return (
        <>
          <ellipse cx="60" cy="64" rx="11" ry="9" fill={C.seed} />
          <path d="M55 60 Q60 56 65 60" fill="none" strokeWidth="2" />
        </>
      )
    case 'sprout':
      return (
        <>
          <rect x="57" y="48" width="6" height="24" rx="3" fill={C.stem} />
          <path d="M58 58 Q44 58 40 46 Q54 42 58 58 Z" fill={C.leaf} />
          <path d="M62 54 Q74 52 80 40 Q66 38 62 54 Z" fill={C.leaf} />
        </>
      )
    case 'sapling':
      return (
        <>
          <rect x="57" y="30" width="6" height="42" rx="3" fill={C.stem} />
          <path d="M58 60 Q44 60 38 48 Q52 44 58 60 Z" fill={C.leaf} />
          <path d="M62 50 Q76 50 84 38 Q68 34 62 50 Z" fill={C.leaf} />
          <path d="M58 40 Q48 38 44 28 Q55 26 58 40 Z" fill={C.leaf} />
          <path d="M60 31 Q54 22 60 12 Q66 22 60 31 Z" fill={C.leafTip} />
        </>
      )
    case 'young':
      return (
        <>
          <path d="M55 72 L57 46 H63 L65 72 Z" fill={C.trunk} />
          <path
            d="M34 46 Q26 30 42 24 Q46 10 62 12 Q78 8 82 24 Q96 30 86 46 Q80 56 60 52 Q40 56 34 46 Z"
            fill={C.canopy}
          />
          <path d="M61 50.5 Q79 54 85 45 Q91 37 87.5 30 Q79 43 61 45.5 Z" fill={C.canopyShade} stroke="none" />
          <ellipse cx="47" cy="24" rx="7" ry="3.6" fill={C.canopyShine} stroke="none" />
        </>
      )
    case 'tree':
      return <BigCanopy />
    case 'blossom':
      return (
        <>
          <BigCanopy />
          {FLOWERS.map(([cx, cy]) => (
            <g key={`${cx}-${cy}`}>
              <circle cx={cx} cy={cy} r="6" fill={C.petal} />
              <circle cx={cx} cy={cy} r="2.2" fill={C.petalCore} stroke="none" />
            </g>
          ))}
        </>
      )
  }
}
