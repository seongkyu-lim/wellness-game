import { message, type Locale, type MessageArg } from './i18n.js';

/** 아직 문자열로 만들지 않은 문구(키 + 인자). 응답을 만들 때 요청 로케일로 해석한다(Spring LocalizedMessage). */
export interface LocalizedMessage {
  code: string;
  args?: MessageArg[];
  /** Bean Validation 필드 오류처럼 "필드: " 를 앞에 붙일 때 */
  prefix?: string;
  /** Bean Validation 제약 속성 치환({max} 등) */
  named?: Record<string, string | number>;
}

export function resolve(locale: Locale, m: LocalizedMessage): string {
  let text = message(locale, m.code, ...(m.args ?? []));
  for (const [name, value] of Object.entries(m.named ?? {})) text = text.split(`{${name}}`).join(String(value));
  return (m.prefix ?? '') + text;
}

export const REASON_PHRASES: Record<number, string> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  409: 'Conflict',
  413: 'Content Too Large',
  415: 'Unsupported Media Type',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  503: 'Service Unavailable',
};

/**
 * 사용자에게 보여 줄 오류(Spring 의 BadRequestException·UnauthorizedException·ForbiddenException·
 * ServiceUnavailableException·TooManyRequestsException). ApiExceptionHandler 와 같은 JSON 으로 응답한다.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly localized: LocalizedMessage,
    readonly headers: Record<string, string> = {},
    options?: { cause?: unknown },
  ) {
    super(`${status} ${localized.code}`, options);
    this.name = 'ApiError';
  }
}

export const badRequest = (code: string, ...args: MessageArg[]) => new ApiError(400, { code, args });

/** 401 + WWW-Authenticate: Bearer */
export const unauthorized = (code: string, ...args: MessageArg[]) =>
  new ApiError(401, { code, args }, { 'WWW-Authenticate': 'Bearer' });

export const unauthorizedCaused = (cause: unknown, code: string, ...args: MessageArg[]) =>
  new ApiError(401, { code, args }, { 'WWW-Authenticate': 'Bearer' }, { cause });

export const forbidden = (code: string, ...args: MessageArg[]) => new ApiError(403, { code, args });

export const serviceUnavailable = (code: string, ...args: MessageArg[]) => new ApiError(503, { code, args });

export const serviceUnavailableCaused = (cause: unknown, code: string, ...args: MessageArg[]) =>
  new ApiError(503, { code, args }, {}, { cause });

export const tooManyRequests = (retryAfterSeconds: number) =>
  new ApiError(429, { code: 'error.rate-limited', args: [retryAfterSeconds] }, { 'Retry-After': String(retryAfterSeconds) });

/**
 * 사용자용으로 만들지 않은 오류(Spring 의 IllegalArgumentException=400, IllegalStateException=503, 그 밖=500).
 * 상태 코드만 유지하고 문구는 error.generic 으로 바꾼다. 상세는 로그에만 남긴다.
 */
export class InternalError extends Error {
  constructor(
    readonly status: 400 | 500 | 503,
    detail: string,
    options?: { cause?: unknown },
  ) {
    super(detail, options);
    this.name = 'InternalError';
  }
}

/** 본문 JSON 을 읽지 못함(Jackson HttpMessageNotReadableException) → 400 error.unreadable-body */
export class UnreadableBodyError extends Error {
  constructor(detail: string) {
    super(detail);
    this.name = 'UnreadableBodyError';
  }
}

/** 쿼리 파라미터 형식 오류 / 누락 → 400 error.invalid-parameter / error.missing-parameter */
export const invalidParameter = () => badRequest('error.invalid-parameter');
export const missingParameter = () => badRequest('error.missing-parameter');

/** ApiExceptionHandler#error 와 같은 본문 */
export function errorBody(status: number, msg: string) {
  return {
    timestamp: new Date().toISOString(),
    status,
    error: REASON_PHRASES[status] ?? 'Error',
    message: msg,
  };
}

/** 스프링 부트 기본 오류 본문(BasicErrorController: 404·405·415 등) */
export function defaultErrorBody(status: number, path: string) {
  return {
    timestamp: new Date().toISOString(),
    status,
    error: REASON_PHRASES[status] ?? 'Error',
    path,
  };
}
