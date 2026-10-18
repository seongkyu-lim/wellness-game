import App from './App'
import { LegalPage } from './components/LegalPage'
import { legalDocumentFor } from './legal/legal'

/**
 * 주소로 페이지를 고른다. 라우터 라이브러리 없이 /privacy·/terms만 따로 그리고, 나머지(소셜 로그인 콜백 포함)는 App.
 * 페이지 사이 이동은 일반 링크(전체 로드)라 주소 변경을 구독하지 않는다. 호스팅은 모든 경로를 index.html로 돌려준다(vercel.json).
 */
export default function Root() {
  const document = legalDocumentFor(window.location.pathname)
  return document ? <LegalPage document={document} /> : <App />
}
