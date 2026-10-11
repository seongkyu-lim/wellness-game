import type { Plugin } from 'vite'

/** API 주소 기본값(로컬 백엔드). src/api/client.ts가 이 값을 가져다 쓴다. */
export const DEFAULT_API_BASE_URL = 'http://localhost:8080'

/**
 * CSP connect-src에 넣을 API 오리진.
 * - 주소가 설정돼 있으면 그 오리진(경로 제외).
 * - 비어 있으면 개발용 빌드에서는 기본값(localhost), 프로덕션 빌드에서는 null(→ connect-src 'self'만).
 *   프로덕션 번들의 connect-src에 localhost가 들어가지 않게 하기 위해서다.
 */
export function resolveApiOrigin(apiBaseUrl: string | undefined, mode: string): string | null {
  if (apiBaseUrl) {
    return new URL(apiBaseUrl).origin
  }
  return mode === 'production' ? null : new URL(DEFAULT_API_BASE_URL).origin
}

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
export function buildCsp(apiOrigin: string | null): string {
  const directives: Record<string, string[]> = {
    'default-src': ["'self'"],
    'script-src': ["'self'"],
    'style-src': ["'self'", 'https://fonts.googleapis.com'],
    'font-src': ['https://fonts.gstatic.com'],
    'img-src': ["'self'", 'data:'],
    'connect-src': apiOrigin ? ["'self'", apiOrigin] : ["'self'"],
    'object-src': ["'none'"],
    'base-uri': ["'self'"],
    'form-action': ["'self'"],
  }
  return Object.entries(directives)
    .map(([name, sources]) => `${name} ${sources.join(' ')}`)
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
      const apiOrigin = resolveApiOrigin(config.env.VITE_API_BASE_URL as string | undefined, config.mode)
      if (!apiOrigin) {
        // 실패 대신 경고: GitHub Pages 배포는 백엔드 배포 전까지 저장소 변수 VITE_API_BASE_URL이 비어 있는 상태로
        // 랜딩·로그인 화면만 서비스한다(README 'GitHub Pages 배포'). 빌드를 막으면 그 배포가 끊긴다.
        config.logger.warn(
          '[wellness-csp] VITE_API_BASE_URL이 비어 있어 프로덕션 CSP connect-src를 \'self\'로만 제한합니다. API 호출은 차단됩니다.',
        )
      }
      policy = buildCsp(apiOrigin)
    },
    transformIndexHtml() {
      return [{ tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: policy }, injectTo: 'head-prepend' }]
    },
  }
}
