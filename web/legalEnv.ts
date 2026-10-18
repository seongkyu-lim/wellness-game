import type { Plugin } from 'vite'
import { isVercelBuild } from './csp'

/**
 * 개인정보 처리방침·이용약관의 연락 이메일과 시행일. 실제 값은 빌드 환경변수로 주입한다.
 * - VITE_PRIVACY_CONTACT_EMAIL: 개인정보 문의·삭제 요청 이메일
 * - VITE_PRIVACY_EFFECTIVE_DATE: 시행일(YYYY-MM-DD)
 * 로컬·테스트는 아래 자리표시자를 그대로 쓰고, Vercel 빌드는 자리표시자가 남으면 실패한다(legalEnvPlugin).
 */
export const PLACEHOLDER_CONTACT_EMAIL = 'privacy@example.com'
export const PLACEHOLDER_EFFECTIVE_DATE = 'YYYY-MM-DD'

export interface LegalEnv {
  contactEmail: string
  effectiveDate: string
}

type Env = Record<string, string | undefined>

export function resolveLegalEnv(env: Env): LegalEnv {
  return {
    contactEmail: env.VITE_PRIVACY_CONTACT_EMAIL?.trim() || PLACEHOLDER_CONTACT_EMAIL,
    effectiveDate: env.VITE_PRIVACY_EFFECTIVE_DATE?.trim() || PLACEHOLDER_EFFECTIVE_DATE,
  }
}

const RESERVED_EMAIL_DOMAIN = /@(?:[^@]+\.)?example\.(?:com|org|net)$/i
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** 운영에 내보내면 안 되는 값(자리표시자·예시 도메인·형식 오류)의 환경변수 이름과 이유. 문제가 없으면 빈 배열. */
export function findLegalPlaceholders({ contactEmail, effectiveDate }: LegalEnv): string[] {
  const problems: string[] = []
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contactEmail) || RESERVED_EMAIL_DOMAIN.test(contactEmail)) {
    problems.push(`VITE_PRIVACY_CONTACT_EMAIL(현재 "${contactEmail}")`)
  }
  if (!ISO_DATE.test(effectiveDate) || Number.isNaN(Date.parse(`${effectiveDate}T00:00:00Z`))) {
    problems.push(`VITE_PRIVACY_EFFECTIVE_DATE(현재 "${effectiveDate}", YYYY-MM-DD 날짜 필요)`)
  }
  return problems
}

/** Vercel 빌드(VERCEL=1)에서 개인정보 처리방침 자리표시자가 남아 있으면 빌드를 실패시킨다. */
export function legalEnvPlugin(): Plugin {
  return {
    name: 'wellness-legal-env',
    apply: 'build',
    configResolved(config) {
      if (!isVercelBuild()) {
        return
      }
      const problems = findLegalPlaceholders(resolveLegalEnv(config.env as Env))
      if (problems.length > 0) {
        throw new Error(
          `[wellness-legal-env] 개인정보 처리방침에 자리표시자가 남아 있습니다. Vercel 환경변수에 실제 값을 설정하세요: ${problems.join(', ')}`,
        )
      }
    },
  }
}
