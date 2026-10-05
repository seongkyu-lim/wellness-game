import type { Language } from '../i18n/dictionaries'
import type { Provider } from './auth'

export interface LoginProviderLayout {
  /** 기본으로 펼쳐 보이는 provider (노출 순서대로) */
  primary: readonly Provider[]
  /** "다른 방법으로 로그인" 접힌 영역에 넣을 provider (노출 순서대로) */
  secondary: readonly Provider[]
}

// 언어별 노출 규칙. Record<Language, ...>라 언어가 추가되면 컴파일 에러로 누락을 알 수 있다.
// 서버는 모든 provider를 항상 받으므로 보안 경계가 아니라 UX 정렬일 뿐이다.
const LAYOUTS: Record<Language, LoginProviderLayout> = {
  ko: { primary: ['kakao', 'naver', 'google'], secondary: [] },
  en: { primary: ['google'], secondary: ['kakao', 'naver'] },
}

/** 현재 UI 언어에 맞는 로그인 버튼 순서와 접힘 여부를 결정하는 순수 함수. */
export function getLoginProviderLayout(lang: Language): LoginProviderLayout {
  return LAYOUTS[lang]
}
