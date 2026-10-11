import type { Message } from '../i18n/core'
import { useI18n } from '../i18n/I18nProvider'

/**
 * 에러·안내 배너. 번역된 문자열이 아니라 Message를 받아 렌더링할 때 번역하므로
 * 배너가 떠 있는 동안 언어를 바꿔도 새 언어로 다시 그려진다. (서버 원문 `{ raw }`는 그대로 표시)
 */
export function ErrorBanner({ message }: { message: Message | null }) {
  const { tMessage } = useI18n()
  if (!message) {
    return null
  }
  return (
    <div className="error-banner" role="alert">
      {tMessage(message)}
    </div>
  )
}
