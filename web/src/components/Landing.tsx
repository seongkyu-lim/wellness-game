import { useEffect, useState, type ReactNode } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import type { MessageKey } from '../i18n/core'
import { GROWTH_STAGES } from '../lib/growthStage'
import { CharacterAvatar } from './CharacterAvatar'
import { HeroScene } from './HeroScene'
import { IconChart, IconLock, IconMoon, IconPhone, IconRun, IconSprout, IconWalk } from './Icons'
import { Quest } from './Quest'

const STAGE_INTERVAL_MS = 2200

const STEPS: { icon: ReactNode; tint: string; title: MessageKey; body: MessageKey }[] = [
  { icon: <IconPhone size={26} />, tint: 'var(--mint)', title: 'landing.step1Title', body: 'landing.step1Body' },
  { icon: <IconChart size={26} />, tint: 'var(--yellow)', title: 'landing.step2Title', body: 'landing.step2Body' },
  { icon: <IconSprout size={26} />, tint: 'var(--pink)', title: 'landing.step3Title', body: 'landing.step3Body' },
]

const NOTES: { icon: ReactNode; tint: string; title: MessageKey; body: MessageKey }[] = [
  { icon: <IconChart size={24} />, tint: 'var(--mint)', title: 'landing.webTitle', body: 'landing.webBody' },
  { icon: <IconLock size={24} />, tint: 'var(--lilac)', title: 'landing.privacyTitle', body: 'landing.privacyBody' },
]

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

const LAST_STAGE = GROWTH_STAGES.length - 1

/**
 * 히어로 장면의 새싹이가 씨앗 → 개화를 한 번만 차례로 보여 주고 개화에서 멈춘다.
 * (끝없이 바뀌는 자동 움직임을 피한다 — WCAG 2.2.2) 동작 줄이기 설정이면 처음부터 개화를 보여 준다.
 */
function useGrowingStage(): number {
  const [index, setIndex] = useState(prefersReducedMotion() ? LAST_STAGE : 0)
  useEffect(() => {
    if (index >= LAST_STAGE) {
      return
    }
    const timer = setTimeout(() => setIndex(index + 1), STAGE_INTERVAL_MS)
    return () => clearTimeout(timer)
  }, [index])
  return index
}

interface Props {
  /** 로그인 카드 — 랜딩 맨 아래 #login 구역에 놓인다. */
  account: ReactNode
}

/** 로그인 전 서비스 소개 메인 페이지. */
export function Landing({ account }: Props) {
  const { t } = useI18n()
  const stage = GROWTH_STAGES[useGrowingStage()]

  return (
    <div className="landing">
      <section className="landing-hero" aria-label={t('landing.label')}>
        <div className="landing-copy">
          <span className="chip pink">{t('landing.eyebrow')}</span>
          <h2 className="landing-title">{t('landing.title')}</h2>
          <p className="landing-lead">{t('landing.lead')}</p>
          <div className="landing-ctas">
            <a className="btn btn-cta" href="#login">
              {t('landing.ctaLogin')}
            </a>
            <a className="btn" href="#how">
              {t('landing.ctaHow')}
            </a>
          </div>
        </div>

        <HeroScene
          asImage
          level={stage.minLevel}
          bubble={t('landing.bubble')}
          badge={t('character.stageBadge', { level: stage.minLevel, stage: t(`stage.${stage.key}`) })}
          label={t('landing.sceneLabel')}
        />
      </section>

      <section id="how" tabIndex={-1} className="landing-section" aria-labelledby="how-title">
        <h2 id="how-title" className="section-title">
          {t('landing.howTitle')}
        </h2>
        <ol className="landing-steps">
          {STEPS.map((step, i) => (
            <li className="card landing-step" key={step.title}>
              <span className="step-no" aria-hidden>
                {i + 1}
              </span>
              <span className="quest-icon" style={{ background: step.tint }}>
                {step.icon}
              </span>
              <h3>{t(step.title)}</h3>
              <p className="hint">{t(step.body)}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="landing-section" aria-labelledby="stats-title">
        <h2 id="stats-title" className="section-title">
          {t('landing.statsTitle')}
        </h2>
        <div className="landing-stats">
          <Quest
            icon={<IconWalk size={24} />}
            tint="var(--mint)"
            title={t('landing.activitySteps')}
            sub={t('landing.statSteps', { stat: `DISC ${t('stat.discipline')}` })}
          />
          <Quest
            icon={<IconRun size={24} />}
            tint="var(--peach)"
            title={t('landing.activityWorkout')}
            sub={t('landing.statWorkout', { stat1: `STR ${t('stat.str')}`, stat2: `VIT ${t('stat.vit')}` })}
          />
          <Quest
            icon={<IconMoon size={24} />}
            tint="var(--lilac)"
            title={t('landing.activitySleep')}
            sub={t('landing.statSleep', { stat: `REC ${t('stat.recovery')}` })}
          />
        </div>
      </section>

      <section className="landing-section" aria-labelledby="stages-title">
        <h2 id="stages-title" className="section-title">
          {t('landing.stagesTitle')}
        </h2>
        <ol className="landing-stages">
          {GROWTH_STAGES.map((s) => (
            <li key={s.key} className={`stage-card${s.key === stage.key ? ' current' : ''}`}>
              <CharacterAvatar level={s.minLevel} size={84} decorative />
              <span className="stage-name">{t(`stage.${s.key}`)}</span>
              <span className="hint">{t('landing.stageLevel', { level: s.minLevel })}</span>
            </li>
          ))}
        </ol>
      </section>

      <section className="landing-section landing-duo">
        {NOTES.map((note) => (
          <div className="card landing-note" key={note.title}>
            <span className="quest-icon" style={{ background: note.tint }}>
              {note.icon}
            </span>
            <div>
              <h2 className="note-title">{t(note.title)}</h2>
              <p className="hint">{t(note.body)}</p>
            </div>
          </div>
        ))}
      </section>

      <section id="login" tabIndex={-1} className="landing-section landing-login">
        {account}
      </section>

      <footer className="landing-footer hint">{t('landing.footer')}</footer>
    </div>
  )
}
