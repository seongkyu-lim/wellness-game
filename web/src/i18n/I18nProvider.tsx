import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  detectLanguage,
  intlLocale,
  saveLanguage,
  setLanguage as setGlobalLanguage,
  translate,
  translateMessage,
  type Language,
  type Message,
  type MessageKey,
  type Params,
} from './core'

interface I18n {
  lang: Language
  setLang: (lang: Language) => void
  t: (key: MessageKey, params?: Params) => string
  /** 보관해 둔 Message(`{ key, params } | { raw }`)를 현재 언어로 그린다. */
  tMessage: (message: Message) => string
  formatNumber: (value: number) => string
  formatDate: (date: Date, options: Intl.DateTimeFormatOptions) => string
}

const I18nContext = createContext<I18n | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Language>(detectLanguage)

  // React 밖(API 클라이언트)과 <html lang>을 현재 언어와 맞춘다.
  setGlobalLanguage(lang)
  useEffect(() => {
    document.documentElement.lang = lang
    document.title = translate(lang, 'app.name')
  }, [lang])

  const setLang = useCallback((next: Language) => {
    saveLanguage(next)
    setLangState(next)
  }, [])

  const value = useMemo<I18n>(() => {
    const locale = intlLocale(lang)
    return {
      lang,
      setLang,
      t: (key, params) => translate(lang, key, params),
      tMessage: (message) => translateMessage(lang, message),
      formatNumber: (n) => new Intl.NumberFormat(locale).format(n),
      formatDate: (date, options) => new Intl.DateTimeFormat(locale, options).format(date),
    }
  }, [lang, setLang])

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18n {
  const ctx = useContext(I18nContext)
  if (!ctx) {
    throw new Error('useI18n must be used within I18nProvider')
  }
  return ctx
}
