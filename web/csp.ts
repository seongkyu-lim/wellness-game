import type { Plugin } from 'vite'

/** src/api/client.ts의 기본값과 같아야 한다. */
export const DEFAULT_API_BASE_URL = 'http://localhost:8080'

/**
 * 프로덕션 빌드용 Content-Security-Policy.
 *
 * - script-src 'self': 번들 스크립트만 허용. 인라인 스크립트·eval 없음.
 * - style-src: 번들 CSS + Google Fonts 스타일시트. React의 `style={{...}}`는 CSSOM으로 적용돼
 *   style-src 'unsafe-inline' 없이도 동작한다(마크업 속성이 아님).
 * - font-src: Google Fonts 폰트 파일.
 * - connect-src: fetch 대상인 API 오리진만. 소셜 로그인(카카오·네이버·구글)은 fetch가 아니라 최상위 페이지 이동
 *   (`window.location.href`)이라 connect-src 대상이 아니며, 코드 교환은 백엔드가 서버 쪽에서 한다. 그래서 넣지 않는다.
 * - img-src 'self' data:: 이미지 파일을 쓰지 않고 SVG는 인라인 요소다. favicon 등 같은 오리진과 data URI만 둔다.
 * - frame-ancestors는 meta로 지정할 수 없어 제외한다(README의 서버 헤더 안내 참고).
 */
export function buildCsp(apiBaseUrl: string | undefined): string {
  const apiOrigin = new URL(apiBaseUrl || DEFAULT_API_BASE_URL).origin
  const directives: Record<string, string[]> = {
    'default-src': ["'self'"],
    'script-src': ["'self'"],
    'style-src': ["'self'", 'https://fonts.googleapis.com'],
    'font-src': ['https://fonts.gstatic.com'],
    'img-src': ["'self'", 'data:'],
    'connect-src': ["'self'", apiOrigin],
    'object-src': ["'none'"],
    'base-uri': ["'self'"],
    'form-action': ["'self'"],
  }
  return Object.entries(directives)
    .map(([name, sources]) => `${name} ${[...new Set(sources)].join(' ')}`)
    .join('; ')
}

/**
 * 빌드 결과(index.html)에만 CSP meta를 주입한다. 개발 서버는 Vite HMR·React Refresh가 인라인 스크립트와
 * 웹소켓을 쓰므로 적용하지 않는다(apply: 'build').
 */
export function cspPlugin(): Plugin {
  let policy = ''
  return {
    name: 'wellness-csp',
    apply: 'build',
    configResolved(config) {
      policy = buildCsp(config.env.VITE_API_BASE_URL as string | undefined)
    },
    transformIndexHtml() {
      return [{ tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: policy }, injectTo: 'head-prepend' }]
    },
  }
}
