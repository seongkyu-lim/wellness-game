import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { I18nProvider } from './i18n/I18nProvider'
import Root from './Root'
import './theme.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <Root />
    </I18nProvider>
  </StrictMode>,
)
