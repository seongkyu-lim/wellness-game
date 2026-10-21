import type { Context } from 'hono';
import { parseLocalDate, type LocalDate } from '../lib/dates.js';
import { ApiError, UnreadableBodyError } from '../lib/errors.js';
import { Instant } from '../lib/instant.js';
import { NUMERIC_STRING } from '../lib/patterns.js';

/**
 * 요청 본문 처리. Spring 과 같은 두 단계로 나눈다.
 * 1) 역직렬화(Jackson): JSON 형식·타입이 틀리면 400 error.unreadable-body (필드 이름을 드러내지 않음)
 *    - 문자열 필드에 숫자·불리언이 오면 문자열로, 숫자 필드에 숫자 문자열이 오면 숫자로 바꾼다(Jackson 기본 강제 변환)
 *    - 모르는 필드는 무시한다(FAIL_ON_UNKNOWN_PROPERTIES=false)
 * 2) 제약 검증(Bean Validation): 선언 순서대로 보고 첫 위반을 "필드: 문구" 로 400 응답한다
 */

export type JsonObject = Record<string, unknown>;

/** 본문을 JSON 객체로 읽는다. Content-Type 이 JSON 이 아니면 415(Spring 기본 응답) */
export async function readJsonObject(c: Context): Promise<JsonObject> {
  const contentType = c.req.header('content-type') ?? '';
  if (!/^application\/([a-z0-9.+-]*\+)?json\b/i.test(contentType.trim())) {
    throw new UnsupportedMediaTypeError();
  }
  const text = await c.req.text();
  if (!text.trim()) throw new UnreadableBodyError('Required request body is missing');
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new UnreadableBodyError('JSON parse error');
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new UnreadableBodyError('Request body must be a JSON object');
  }
  return parsed as JsonObject;
}

export class UnsupportedMediaTypeError extends Error {
  constructor() {
    super('Unsupported Media Type');
    this.name = 'UnsupportedMediaTypeError';
  }
}

const unreadable = (field: string) => new UnreadableBodyError(`Cannot deserialize field '${field}'`);

/** String: 숫자·불리언은 문자열로, 객체·배열은 오류 */
export function str(obj: JsonObject, field: string): string | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  throw unreadable(field);
}

const INT_MIN = -2_147_483_648;
const INT_MAX = 2_147_483_647;

/** Integer: 정수, 소수(버림), 숫자 문자열. 빈 문자열은 null */
export function int(obj: JsonObject, field: string): number | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  let n: number;
  if (typeof value === 'number') n = value;
  else if (typeof value === 'string') {
    if (value.trim() === '') return null;
    if (!NUMERIC_STRING.test(value)) throw unreadable(field);
    n = Number(value);
  } else throw unreadable(field);
  if (!Number.isFinite(n)) throw unreadable(field);
  const truncated = Math.trunc(n);
  if (truncated < INT_MIN || truncated > INT_MAX) throw unreadable(field);
  return truncated;
}

/** BigDecimal: 숫자 또는 숫자 문자열 */
export function decimal(obj: JsonObject, field: string): number | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string') {
    if (value.trim() === '') return null;
    if (NUMERIC_STRING.test(value)) return Number(value);
  }
  throw unreadable(field);
}

/** enum: 이름(대소문자 구분) 또는 서수(Jackson 기본 허용) */
export function enumOf<T extends string>(obj: JsonObject, field: string, values: readonly T[]): T | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (typeof value === 'string') {
    if (value === '') return null;
    if ((values as readonly string[]).includes(value)) return value as T;
  }
  if (typeof value === 'number' && Number.isInteger(value) && value >= 0 && value < values.length) return values[value]!;
  throw unreadable(field);
}

/** LocalDate: "yyyy-MM-dd" 또는 [yyyy, M, d] */
export function localDate(obj: JsonObject, field: string): LocalDate | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (typeof value === 'string') {
    if (value.trim() === '') return null;
    const parsed = parseLocalDate(value.trim());
    if (parsed) return parsed;
  }
  if (Array.isArray(value) && value.length === 3 && value.every((v) => Number.isInteger(v))) {
    const [y, m, d] = value as number[];
    const parsed = parseLocalDate(`${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`);
    if (parsed) return parsed;
  }
  throw unreadable(field);
}

/** Instant: ISO-8601(Z·오프셋) 문자열 또는 epoch 초 숫자 */
export function instant(obj: JsonObject, field: string): Instant | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (typeof value === 'string') {
    if (value.trim() === '') return null;
    const parsed = Instant.parse(value.trim());
    if (parsed) return parsed;
  }
  if (typeof value === 'number') {
    const parsed = Instant.fromEpochSecondsNumber(value);
    if (parsed) return parsed;
  }
  throw unreadable(field);
}

/** List: 배열이 아니면 오류. 원소는 아직 보지 않는다(개수 검증 뒤 listItem 으로 하나씩 읽는다) */
export function list(obj: JsonObject, field: string): unknown[] | null {
  const value = obj[field];
  if (value === undefined || value === null) return null;
  if (!Array.isArray(value)) throw unreadable(field);
  return value;
}

/** List<객체> 의 원소: 객체가 아니면 오류 */
export function listItem(item: unknown, field: string): JsonObject {
  if (item === null || typeof item !== 'object' || Array.isArray(item)) throw unreadable(field);
  return item as JsonObject;
}

// ---- Bean Validation ----

/** 필드 하나의 제약 검사기. 위반이면 ApiError(400, "필드: 문구") 를 돌려준다 */
export type Check = () => ApiError | null;

const violation = (field: string, code: string, named?: Record<string, number>) =>
  new ApiError(400, { code, prefix: `${field}: `, named });

/** 선언 순서대로 검사하고 첫 위반을 던진다(Spring: getFieldErrors().findFirst()) */
export function validate(...checks: Check[]): void {
  for (const check of checks) {
    const error = check();
    if (error) throw error;
  }
}

const isBlank = (value: string | null) => value === null || value.trim() === '';

export const notBlank = (field: string, value: string | null): Check => () =>
  isBlank(value) ? violation(field, 'jakarta.validation.constraints.NotBlank.message') : null;

export const notNull = (field: string, value: unknown): Check => () =>
  value === null || value === undefined ? violation(field, 'jakarta.validation.constraints.NotNull.message') : null;

export const notEmpty = (field: string, value: unknown[] | null): Check => () =>
  value === null || value.length === 0 ? violation(field, 'jakarta.validation.constraints.NotEmpty.message') : null;

export const positiveOrZero = (field: string, value: number | null): Check => () =>
  value !== null && value < 0 ? violation(field, 'jakarta.validation.constraints.PositiveOrZero.message') : null;

/** @Size(min, max): null 은 통과. 문자열 길이는 UTF-16 코드 단위(Java String.length) */
export const size = (field: string, value: string | unknown[] | null, min: number, max: number, code: string): Check => () =>
  value !== null && (value.length < min || value.length > max) ? violation(field, code, { min, max }) : null;

/** @Pattern: null 은 통과 */
export const pattern = (field: string, value: string | null, regex: RegExp, code: string): Check => () =>
  value !== null && !regex.test(value) ? violation(field, code) : null;

/** @MaxUtf8Bytes(value): UTF-8 바이트 수 상한(BCrypt 72바이트 한계). null 은 통과 */
export const maxUtf8Bytes = (field: string, value: string | null, max: number, code: string): Check => () =>
  value !== null && new TextEncoder().encode(value).length > max ? violation(field, code, { value: max }) : null;
