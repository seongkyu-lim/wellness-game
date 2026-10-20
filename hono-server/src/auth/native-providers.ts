import type { AppConfig } from '../config.js';
import { ApiError, badRequest, serviceUnavailable, serviceUnavailableCaused, unauthorized, unauthorizedCaused } from '../lib/errors.js';
import { logger } from '../lib/logger.js';
import { asLong, asText, HttpClient, HttpStatusError, path } from './http-client.js';
import { toResult, type SocialAuthService, type SocialLoginResult } from './social.js';

/** 미설정(503). 설정 이름은 서버 로그에만 남긴다(Spring NativeAuthSupport) */
export function unavailable(provider: string, settingHint: string, messageCode: string): ApiError {
  logger.error(`${provider} 네이티브 로그인 설정 없음: ${settingHint} 를 확인하세요.`);
  return serviceUnavailable(messageCode);
}

/** 빈 토큰(400) */
export function requireToken(token: string | null | undefined, messageCode: string): asserts token is string {
  if (!token || !token.trim()) throw badRequest(messageCode);
}

const isBlank = (value: string | null | undefined) => !value || !value.trim();

/**
 * iOS 카카오 SDK 액세스 토큰 검증(Spring KakaoNativeAuthService).
 * access_token_info 로 유효성·발급 앱(app_id)을 확인하고, 웹과 같은 프로필 호출·userId 규칙을 쓴다.
 */
export class KakaoNativeAuthService {
  constructor(
    private readonly config: AppConfig['oauth']['kakao'],
    private readonly http: HttpClient,
    private readonly social: SocialAuthService,
  ) {}

  async authenticate(accessToken: string): Promise<SocialLoginResult> {
    if (!this.config.nativeAppId) {
      throw unavailable('카카오', 'OAUTH_KAKAO_NATIVE_APP_ID 환경변수', 'auth.kakao.unavailable');
    }
    requireToken(accessToken, 'auth.native.access-token-blank');
    try {
      const tokenInfo = await this.http.getJson(this.config.accessTokenInfoUri, { Authorization: `Bearer ${accessToken}` });
      const id = asLong(path(tokenInfo, 'id'));
      if (tokenInfo === null || id <= 0 || asLong(path(tokenInfo, 'expires_in')) <= 0) {
        throw unauthorized('auth.kakao.access-token-invalid');
      }
      if (this.config.nativeAppId !== (asText(path(tokenInfo, 'app_id'), '') ?? '')) {
        throw unauthorized('auth.kakao.wrong-app');
      }
      const profile = await this.social.fetchProfile('kakao', accessToken);
      if (profile === null || asLong(path(profile, 'id')) !== id) {
        throw unauthorized('auth.kakao.access-token-invalid');
      }
      return toResult('kakao', profile);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (error instanceof HttpStatusError && error.isClientError) {
        // 쿼터 초과는 토큰 문제가 아니라 일시 장애다
        if (error.status === 429) throw serviceUnavailableCaused(error, 'auth.kakao.verification-failed');
        // 카카오는 무효·만료 토큰에 401(code -401)을, 형식 오류에 400 을 준다
        throw unauthorizedCaused(error, 'auth.kakao.access-token-invalid');
      }
      // 5xx·타임아웃·연결 실패
      throw serviceUnavailableCaused(error, 'auth.kakao.verification-failed');
    }
  }
}

/**
 * iOS 네이버 SDK 토큰 검증(Spring NaverNativeAuthService).
 * 네이버는 토큰의 발급 앱을 알려 주는 API 가 없어서, refresh token 을 이 서버의 client_id·secret 으로 갱신해
 * 앱 소유를 확인하고 갱신으로 받은 새 access token 으로만 프로필을 조회한다(요청의 accessToken 은 쓰지 않음).
 */
export class NaverNativeAuthService {
  constructor(
    private readonly config: AppConfig['oauth']['naver'],
    private readonly http: HttpClient,
    private readonly social: SocialAuthService,
  ) {}

  async authenticate(accessToken: string, refreshToken: string): Promise<SocialLoginResult> {
    const naver = this.config;
    if (isBlank(naver.clientId) || isBlank(naver.clientSecret) || isBlank(naver.tokenUri) || isBlank(naver.profileUri)) {
      throw unavailable(
        '네이버',
        'OAUTH_NAVER_CLIENT_ID·OAUTH_NAVER_CLIENT_SECRET 환경변수와 oauth.naver.token-uri/profile-uri',
        'auth.naver.unavailable',
      );
    }
    requireToken(accessToken, 'auth.native.access-token-blank');
    requireToken(refreshToken, 'auth.native.refresh-token-blank');
    try {
      const response = await this.http.postForm(naver.tokenUri, {
        grant_type: 'refresh_token',
        client_id: naver.clientId,
        client_secret: naver.clientSecret,
        refresh_token: refreshToken.trim(),
      });
      // 네이버는 다른 앱의 refresh token·잘못된 자격 증명에도 200 + {"error": ...} 로 응답할 수 있다
      const ownedAccessToken = asText(path(response, 'access_token'), '') ?? '';
      const errorNode = path(response, 'error');
      if (!ownedAccessToken.trim() || (errorNode !== undefined && errorNode !== null)) {
        throw unauthorized('auth.naver.access-token-invalid');
      }
      const profile = await this.social.fetchProfile('naver', ownedAccessToken);
      if (profile === null || asText(path(profile, 'resultcode'), '') !== '00') {
        throw unauthorized('auth.naver.access-token-invalid');
      }
      return toResult('naver', profile);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (error instanceof HttpStatusError && error.isClientError) {
        throw unauthorizedCaused(error, 'auth.naver.access-token-invalid');
      }
      throw serviceUnavailableCaused(error, 'auth.naver.verification-failed');
    }
  }
}
