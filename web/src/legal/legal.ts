import { resolveLegalEnv } from '../../legalEnv'
import type { MessageKey } from '../i18n/core'

/**
 * 개인정보 문의·삭제 요청 연락처와 시행일. 빌드 환경변수 VITE_PRIVACY_CONTACT_EMAIL·VITE_PRIVACY_EFFECTIVE_DATE에서 오고,
 * 없으면 자리표시자다(Vercel 빌드는 자리표시자면 실패 — legalEnv.ts).
 */
export const { contactEmail: PRIVACY_CONTACT_EMAIL, effectiveDate: LEGAL_EFFECTIVE_DATE } = resolveLegalEnv(import.meta.env)

/** 호스팅이 index.html로 돌려줘야 하는 앱 경로. 소셜 로그인 콜백은 '/'(쿼리 ?code=)로 돌아온다. vercel.json과 맞춘다. */
export const APP_ROUTES = ['/', '/privacy', '/terms'] as const

export type LegalDocument = 'privacy' | 'terms'

interface LegalSection {
  title: MessageKey
  body: MessageKey
}

export const LEGAL_DOCUMENTS: Record<LegalDocument, { title: MessageKey; intro: MessageKey; sections: LegalSection[] }> = {
  privacy: {
    title: 'legal.privacy.title',
    intro: 'legal.privacy.intro',
    sections: [
      { title: 'legal.privacy.collectTitle', body: 'legal.privacy.collectBody' },
      { title: 'legal.privacy.notSentTitle', body: 'legal.privacy.notSentBody' },
      { title: 'legal.privacy.purposeTitle', body: 'legal.privacy.purposeBody' },
      { title: 'legal.privacy.healthTitle', body: 'legal.privacy.healthBody' },
      { title: 'legal.privacy.sharingTitle', body: 'legal.privacy.sharingBody' },
      { title: 'legal.privacy.transferTitle', body: 'legal.privacy.transferBody' },
      { title: 'legal.privacy.loginTitle', body: 'legal.privacy.loginBody' },
      { title: 'legal.privacy.retentionTitle', body: 'legal.privacy.retentionBody' },
      { title: 'legal.privacy.rightsTitle', body: 'legal.privacy.rightsBody' },
      { title: 'legal.privacy.contactTitle', body: 'legal.privacy.contactBody' },
      { title: 'legal.privacy.changesTitle', body: 'legal.privacy.changesBody' },
    ],
  },
  terms: {
    title: 'legal.terms.title',
    intro: 'legal.terms.intro',
    sections: [
      { title: 'legal.terms.serviceTitle', body: 'legal.terms.serviceBody' },
      { title: 'legal.terms.accountTitle', body: 'legal.terms.accountBody' },
      { title: 'legal.terms.medicalTitle', body: 'legal.terms.medicalBody' },
      { title: 'legal.terms.conductTitle', body: 'legal.terms.conductBody' },
      { title: 'legal.terms.changesTitle', body: 'legal.terms.changesBody' },
      { title: 'legal.terms.liabilityTitle', body: 'legal.terms.liabilityBody' },
      { title: 'legal.terms.withdrawTitle', body: 'legal.terms.withdrawBody' },
      { title: 'legal.terms.privacyTitle', body: 'legal.terms.privacyBody' },
      { title: 'legal.terms.contactTitle', body: 'legal.terms.contactBody' },
    ],
  },
}

/** 앱 기준 경로(BASE_URL)를 붙인 페이지 주소. */
export function pagePath(page: LegalDocument | ''): string {
  return `${import.meta.env.BASE_URL}${page}`
}

/** 현재 주소가 법적 고지 페이지면 그 종류, 아니면 null. 끝의 '/'는 무시한다. */
export function legalDocumentFor(pathname: string): LegalDocument | null {
  const base = import.meta.env.BASE_URL
  const path = pathname.startsWith(base) ? pathname.slice(base.length) : pathname
  const page = path.replace(/\/+$/, '')
  return page === 'privacy' || page === 'terms' ? page : null
}
