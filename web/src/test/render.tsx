import { render, type RenderOptions } from '@testing-library/react'
import type { ReactElement, ReactNode } from 'react'
import type { Language } from '../i18n/core'
import { I18nProvider } from '../i18n/I18nProvider'

/** 저장된 언어를 정한 뒤 I18nProvider로 감싸 그린다. rerender도 같은 Provider로 감싼다. */
export function renderWithI18n(ui: ReactElement, { lang = 'ko', ...options }: { lang?: Language } & RenderOptions = {}) {
  localStorage.setItem('wellness.lang', lang)
  const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>
  return render(ui, { wrapper, ...options })
}
