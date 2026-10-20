/** 외부 인증 서버 호출(Spring RestClient 대응). 테스트에서 fetch 를 바꿔 끼울 수 있다. */

export type FetchFn = (input: string, init?: RequestInit) => Promise<Response>;

/** 4xx·5xx 응답(HttpClientErrorException / HttpServerErrorException) */
export class HttpStatusError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`);
    this.name = 'HttpStatusError';
  }

  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500;
  }
}

/** 연결 실패·타임아웃·본문 해석 실패(그 밖의 RestClientException) */
export class HttpTransportError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'HttpTransportError';
  }
}

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export class HttpClient {
  constructor(
    private readonly fetchFn: FetchFn,
    private readonly timeoutMs: number,
  ) {}

  async getJson(url: string, headers: Record<string, string> = {}): Promise<Json> {
    return this.send(url, { method: 'GET', headers: { Accept: 'application/json', ...headers } });
  }

  async getText(url: string): Promise<string> {
    const response = await this.request(url, { method: 'GET' });
    return response.text();
  }

  async postForm(url: string, form: Record<string, string>): Promise<Json> {
    return this.send(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body: new URLSearchParams(form).toString(),
    });
  }

  private async send(url: string, init: RequestInit): Promise<Json> {
    const response = await this.request(url, init);
    const text = await response.text().catch((error: unknown) => {
      throw new HttpTransportError('응답 본문을 읽지 못했습니다.', { cause: error });
    });
    if (!text.trim()) return null;
    try {
      return JSON.parse(text) as Json;
    } catch (error) {
      throw new HttpTransportError('응답 JSON 을 해석하지 못했습니다.', { cause: error });
    }
  }

  private async request(url: string, init: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetchFn(url, { ...init, signal: AbortSignal.timeout(this.timeoutMs) });
    } catch (error) {
      throw new HttpTransportError('외부 서버 호출 실패', { cause: error });
    }
    if (response.status >= 400) {
      await response.body?.cancel().catch(() => {});
      throw new HttpStatusError(response.status);
    }
    return response;
  }
}

/** Jackson JsonNode.path(...) 처럼 없는 경로는 undefined */
export function path(node: Json | undefined, ...keys: string[]): Json | undefined {
  let current: Json | undefined = node;
  for (const key of keys) {
    if (current === null || current === undefined || typeof current !== 'object' || Array.isArray(current)) return undefined;
    current = current[key];
  }
  return current;
}

/** JsonNode.asText(default): 값 노드는 문자열로, null·없음·객체는 기본값 */
export function asText(node: Json | undefined, fallback: string | null): string | null {
  if (node === null || node === undefined || typeof node === 'object') return fallback;
  return String(node);
}

/** JsonNode.asLong(): 숫자·숫자 문자열은 정수로, 그 밖은 0 */
export function asLong(node: Json | undefined): number {
  if (typeof node === 'number') return Math.trunc(node);
  if (typeof node === 'string' && /^\s*[+-]?\d+\s*$/.test(node)) return Number(node.trim());
  if (typeof node === 'boolean') return node ? 1 : 0;
  return 0;
}
