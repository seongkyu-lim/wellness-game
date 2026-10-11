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

/**
 * 화면에 띄울 메시지를 번역된 문자열 대신 보관하는 형태.
 * - `{ key, params }`: 클라이언트 사전 키. 렌더링할 때마다 현재 언어로 번역되므로 언어를 바꾸면 이미 떠 있는 배너도 바뀐다.
 * - `{ raw }`: 서버가 Accept-Language 기준으로 이미 번역해 보낸 문구. 클라이언트가 다시 번역할 수 없다.
 */
export type Message = { key: MessageKey; params?: Params } | { raw: string }

export function translateMessage(lang: Language, message: Message): string {
  return 'raw' in message ? message.raw : translate(lang, message.key, message.params)
}

/**
 * 사용자에게 보여줄 메시지를 함께 실어 나르는 에러.
 * `message`(Error 기본 필드)는 로그용으로 생성 시점 언어로 채우고, 화면에는 `userMessage`를 번역해 쓴다.
 */
export class LocalizedError extends Error {
  constructor(readonly userMessage: Message) {
    super(translateMessage(currentLanguage, userMessage))
    this.name = 'LocalizedError'
  }
}

/**
 * 잡은 에러를 화면용 메시지로 바꾼다. LocalizedError가 아니면(예: fetch의 TypeError "Failed to fetch")
 * 브라우저·런타임 언어의 원문 대신 사전의 대체 문구를 쓴다.
 */
export function toMessage(error: unknown, fallback: MessageKey): Message {
  return error instanceof LocalizedError ? error.userMessage : { key: fallback }
}
