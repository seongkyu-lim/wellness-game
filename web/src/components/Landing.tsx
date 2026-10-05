import { useEffect, useState, type ReactNode } from 'react'
import { useI18n } from '../i18n/I18nProvider'
import type { MessageKey } from '../i18n/core'
import { GROWTH_STAGES } from '../lib/growthStage'
import { CharacterAvatar } from './CharacterAvatar'
import { IconChart, IconLock, IconMoon, IconPhone, IconRun, IconSprout, IconWalk, Sparkle } from './Icons'

const STAGE_INTERVAL_MS = 2200

const STEPS: { icon: ReactNode; tint: string; title: MessageKey; body: MessageKey }[] = [
  { icon: <IconPhone size={26} />, tint: 'var(--mint)', title: 'landing.step1Title', body: 'landing.step1Body' },
  { icon: <IconChart size={26} />, tint: 'var(--yellow)', title: 'landing.step2Title', body: 'landing.step2Body' },
  { icon: <IconSprout size={26} />, tint: 'var(--pink)', title: 'landing.step3Title', body: 'landing.step3Body' },
]

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
}

/** 히어로 장면의 새싹이가 씨앗 → 개화 단계를 차례로 보여 준다. 동작 줄이기 설정이면 줄기 단계에 멈춘다. */
function useCyclingStage(): number {
  const [index, setIndex] = useState(prefersReducedMotion() ? 2 : 0)
  useEffect(() => {
    if (prefersReducedMotion()) {
      return
    }
    const timer = setInterval(() => setIndex((i) => (i + 1) % GROWTH_STAGES.length), STAGE_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [])
  return index
}

interface Props {
  /** 로그인 카드 — 랜딩 맨 아래 #login 구역에 놓인다. */
  account: ReactNode
}

/** 로그인 전 서비스 소개 메인 페이지. */
export function Landing({ account }: Props) {
  const { t } = useI18n()
  const stage = GROWTH_STAGES[useCyclingStage()]

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
            <a className="btn btn-ghost" href="#how">
              {t('landing.ctaHow')}
            </a>
          </div>
        </div>

        <div className="hero landing-scene" role="img" aria-label={t('landing.sceneLabel')}>
          <svg className="hills" viewBox="0 0 390 110" preserveAspectRatio="none" aria-hidden>
            <path className="hill" d="M0 52 Q70 24 150 46 T300 40 T390 50 V110 H0Z" />
            <path className="hill-deep" d="M0 74 Q90 58 190 72 T390 70 V110 H0Z" />
          </svg>
          <p className="bubble" aria-hidden>
            {t('landing.bubble')}
          </p>
          <Sparkle size={26} fill="#ffe066" style={{ right: 34, top: 26 }} />
          <Sparkle size={16} fill="#ffffff" timing="slow" style={{ right: 78, top: 84 }} />
          <Sparkle size={18} fill="#ff9ec4" timing="late" style={{ left: 48, top: 150 }} />
          <div className="mascot bob">
            <CharacterAvatar key={stage.key} level={stage.minLevel} size={210} decorative />
          </div>
          <div className="nameplate" aria-hidden>
            <span className="name-tag">{t('character.name')}</span>
            <span className="level-chip">{t('character.stageBadge', { level: stage.minLevel, stage: t(`stage.${stage.key}`) })}</span>
          </div>
        </div>
      </section>

      <section id="how" className="landing-section" aria-labelledby="how-title">
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
          <StatRow
            icon={<IconWalk size={24} />}
            tint="var(--stat-disc)"
            name={t('landing.activitySteps')}
            text={t('landing.statSteps', { stat: `DISC ${t('stat.discipline')}` })}
          />
          <StatRow
            icon={<IconRun size={24} />}
            tint="var(--stat-str)"
            name={t('landing.activityWorkout')}
            text={t('landing.statWorkout', { stat1: `STR ${t('stat.str')}`, stat2: `VIT ${t('stat.vit')}` })}
          />
          <StatRow
            icon={<IconMoon size={24} />}
            tint="var(--stat-rec)"
            name={t('landing.activitySleep')}
            text={t('landing.statSleep', { stat: `REC ${t('stat.recovery')}` })}
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
        <div className="card landing-note">
          <span className="quest-icon" style={{ background: 'var(--mint)' }}>
            <IconChart size={24} />
          </span>
          <div>
            <h3>{t('landing.webTitle')}</h3>
            <p className="hint">{t('landing.webBody')}</p>
          </div>
        </div>
        <div className="card landing-note">
          <span className="quest-icon" style={{ background: 'var(--lilac)' }}>
            <IconLock size={24} />
          </span>
          <div>
            <h3>{t('landing.privacyTitle')}</h3>
            <p className="hint">{t('landing.privacyBody')}</p>
          </div>
        </div>
      </section>

      <section id="login" className="landing-section landing-login">
        {account}
      </section>

      <footer className="landing-footer hint">{t('landing.footer')}</footer>
    </div>
  )
}

function StatRow({ icon, tint, name, text }: { icon: ReactNode; tint: string; name: string; text: string }) {
  return (
    <div className="quest">
      <span className="quest-icon" style={{ background: tint }}>
        {icon}
      </span>
      <div className="quest-body">
        <span className="quest-title">{name}</span>
        <span className="quest-sub">{text}</span>
      </div>
    </div>
  )
}
