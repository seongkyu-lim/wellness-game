import { DICTIONARIES, type Language, type MessageKey } from './dictionaries'

export type { Language, MessageKey }

const STORAGE_KEY = 'wellness.lang'

export function isLanguage(value: unknown): value is Language {
  return value === 'ko' || value === 'en'
}

/** localStorage 저장값 → navigator.language(ko로 시작하면 ko, 아니면 en) 순으로 결정한다. */
export function detectLanguage(): Language {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (isLanguage(stored)) {
      return stored
    }
  } catch {
    // 저장소 접근이 막힌 환경에서는 브라우저 언어로 넘어간다.
  }
  const nav = typeof navigator === 'undefined' ? '' : navigator.language
  return nav.toLowerCase().startsWith('ko') ? 'ko' : 'en'
}

export function saveLanguage(lang: Language): void {
  try {
    localStorage.setItem(STORAGE_KEY, lang)
  } catch {
    // 저장 실패는 무시한다. 이번 방문 동안에는 메모리 상태로 동작한다.
  }
}

let currentLanguage: Language = detectLanguage()

/** React 밖(API 클라이언트 등)에서 현재 언어를 읽는다. */
export function getLanguage(): Language {
  return currentLanguage
}

export function setLanguage(lang: Language): void {
  currentLanguage = lang
}

export type Params = Record<string, string | number>

export function format(template: string, params?: Params): string {
  if (!params) {
    return template
  }
  return template.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? String(params[name]) : match))
}

export function translate(lang: Language, key: MessageKey, params?: Params): string {
  return format(DICTIONARIES[lang][key], params)
}

/** React 밖에서 쓰는 번역 (현재 언어 기준). */
export function t(key: MessageKey, params?: Params): string {
  return translate(currentLanguage, key, params)
}

export function intlLocale(lang: Language): string {
  return lang === 'ko' ? 'ko-KR' : 'en-US'
}
