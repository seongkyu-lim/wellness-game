import type { AppConfig, OAuthProvider } from '../config.js';
import { ApiError, badRequest, serviceUnavailable, serviceUnavailableCaused, unauthorized } from '../lib/errors.js';
import { logger } from '../lib/logger.js';
import { asLong, asText, HttpClient, path, type Json } from './http-client.js';

export interface SocialLoginResult {
  userId: string;
  displayName: string | null;
}

export type WebProvider = 'kakao' | 'naver' | 'google';
export const SUPPORTED_WEB_PROVIDERS: readonly WebProvider[] = ['kakao', 'naver', 'google'];
/** PKCE code_verifier 가 필수이고 토큰 교환에 넘기는 공급자(#105). 네이버는 PKCE 를 지원하지 않는다 */
const PKCE_PROVIDERS: ReadonlySet<WebProvider> = new Set(['kakao', 'google']);

/**
 * 웹 클라이언트가 받아온 authorization code 를 공급자 토큰·프로필로 교환해 iOS 앱과 같은
 * "{provider}:{식별자}" userId 를 만든다(Spring SocialAuthService).
 */
export class SocialAuthService {
  constructor(
    private readonly oauth: AppConfig['oauth'],
    private readonly http: HttpClient,
  ) {}

  async authenticate(providerName: string, code: string, redirectUri: string, codeVerifier?: string | null): Promise<SocialLoginResult> {
    if (!(SUPPORTED_WEB_PROVIDERS as readonly string[]).includes(providerName)) {
      throw badRequest('auth.social.unsupported-provider', providerName);
    }
    const name = providerName as WebProvider;
    const provider = this.provider(name);
    if (!provider.clientId || !provider.clientId.trim()) {
      // 환경변수 이름은 서버 로그에만 남기고, 응답에는 공급자 이름도 넣지 않는 일반 문구를 쓴다
      logger.error(`${name} 소셜 로그인 설정 없음: OAUTH_${name.toUpperCase()}_* 환경변수를 확인하세요.`);
      throw serviceUnavailable('auth.social.not-configured');
    }
    const pkce = PKCE_PROVIDERS.has(name);
    if (pkce && (!codeVerifier || !codeVerifier.trim())) throw badRequest('auth.social.code-verifier-required');
    try {
      const accessToken = await this.requestAccessToken(name, provider, code, redirectUri, codeVerifier);
      return toResult(name, await this.requestProfile(provider, accessToken));
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw serviceUnavailableCaused(error, 'auth.social.failed', name);
    }
  }

  /** 공급자 액세스 토큰으로 profile-uri 를 호출한다. iOS 네이티브 로그인(카카오·네이버)도 같은 호출을 쓴다 */
  fetchProfile(providerName: WebProvider, accessToken: string): Promise<Json> {
    const provider = this.provider(providerName);
    if (!provider.profileUri || !provider.profileUri.trim()) throw serviceUnavailable('auth.social.failed', providerName);
    return this.requestProfile(provider, accessToken);
  }

  private provider(name: WebProvider): OAuthProvider {
    return this.oauth[name];
  }

  private requestProfile(provider: OAuthProvider, accessToken: string): Promise<Json> {
    return this.http.getJson(provider.profileUri, { Authorization: `Bearer ${accessToken}` });
  }

  private async requestAccessToken(
    name: WebProvider,
    provider: OAuthProvider,
    code: string,
    redirectUri: string,
    codeVerifier?: string | null,
  ): Promise<string> {
    const form: Record<string, string> = { grant_type: 'authorization_code', client_id: provider.clientId };
    if (provider.clientSecret && provider.clientSecret.trim()) form.client_secret = provider.clientSecret;
    form.redirect_uri = redirectUri;
    form.code = code;
    if (codeVerifier && codeVerifier.trim() && PKCE_PROVIDERS.has(name)) form.code_verifier = codeVerifier;
    const response = await this.http.postForm(provider.tokenUri, form);
    const accessToken = asText(path(response, 'access_token'), null);
    if (accessToken === null) throw serviceUnavailable('auth.social.token-issue-failed');
    return accessToken;
  }
}

/**
 * 공급자 프로필을 "{provider}:{식별자}" userId 로 변환한다. 식별자가 비어 있으면 "kakao:0"·"google:" 같은
 * 공용 userId 가 생기지 않도록 401 로 거부한다.
 */
export function toResult(providerName: WebProvider, profile: Json): SocialLoginResult {
  switch (providerName) {
    case 'kakao': {
      // iOS: user.id (회원번호)
      const id = asLong(path(profile, 'id'));
      return {
        userId: userId(providerName, id > 0 ? String(id) : ''),
        displayName: asText(path(profile, 'properties', 'nickname'), null),
      };
    }
    case 'naver': {
      // iOS: profile["id"]
      const response = path(profile, 'response');
      const name = asText(path(response, 'name'), asText(path(response, 'nickname'), null));
      return { userId: userId(providerName, asText(path(response, 'id'), '') ?? ''), displayName: name };
    }
    default:
      // iOS: GIDGoogleUser.userID == OIDC sub
      return {
        userId: userId(providerName, asText(path(profile, 'sub'), '') ?? ''),
        displayName: asText(path(profile, 'name'), null),
      };
  }
}

function userId(providerName: string, providerUserId: string): string {
  if (!providerUserId || !providerUserId.trim()) throw unauthorized('auth.social.failed', providerName);
  return `${providerName}:${providerUserId}`;
}
