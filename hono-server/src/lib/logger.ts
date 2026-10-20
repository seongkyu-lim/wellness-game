/**
 * 한 줄 로그. Spring 처럼 4xx 는 warn(스택 없음), 5xx 는 error(스택 포함)로 남긴다.
 * 토큰·건강 데이터 같은 본문 조각은 로그에 넣지 않는다.
 */
export const logger = {
  info(message: string) {
    console.info(`[INFO] ${message}`);
  },
  warn(message: string) {
    console.warn(`[WARN] ${message}`);
  },
  error(message: string, error?: unknown) {
    if (error !== undefined) console.error(`[ERROR] ${message}`, error);
    else console.error(`[ERROR] ${message}`);
  },
};
