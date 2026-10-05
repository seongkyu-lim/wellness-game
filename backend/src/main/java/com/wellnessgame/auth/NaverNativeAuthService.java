package com.wellnessgame.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * iOS 네이버 SDK 가 발급한 액세스 토큰으로 프로필(/v1/nid/me)을 조회해 웹 코드 교환과 같은
 * "naver:{id}" userId 를 만든다. 프로필 조회 성공을 토큰 유효성의 근거로 삼는다.
 *
 * <p><b>보안 한계</b>: 네이버는 액세스 토큰이 어느 client_id(앱)에 발급됐는지 확인하는 공식 introspection API 를
 * 제공하지 않는다. 따라서 다른 네이버 앱이 같은 사용자에게서 받은 유효한 토큰도 이 엔드포인트를 통과한다
 * (토큰 대체 공격). 카카오(app_id)·구글/애플(aud)과 달리 앱 소유 검증이 없다는 점을 감안해야 한다.
 * 활성화 여부는 웹과 같은 oauth.naver.client-id(OAUTH_NAVER_CLIENT_ID) 설정으로 판단한다.
 */
@Service
public class NaverNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(NaverNativeAuthService.class);
    private static final String PROVIDER = "naver";
    private static final String NAVER_SUCCESS = "00";

    private final SocialAuthService socialAuthService;
    private final OAuthProperties properties;

    public NaverNativeAuthService(SocialAuthService socialAuthService, OAuthProperties properties) {
        this.socialAuthService = socialAuthService;
        this.properties = properties;
    }

    public SocialLoginResult authenticate(String accessToken) {
        OAuthProperties.Provider naver = properties == null ? null : properties.naver();
        if (naver == null || !naver.configured() || naver.profileUri() == null || naver.profileUri().isBlank()) {
            log.error("네이버 네이티브 로그인 설정 없음: OAUTH_NAVER_CLIENT_ID 환경변수와 oauth.naver.profile-uri 를 확인하세요.");
            throw new IllegalStateException(Messages.get("auth.naver.unavailable"));
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth.native.access-token-blank"));
        }

        JsonNode profile;
        try {
            profile = socialAuthService.fetchProfile(PROVIDER, accessToken);
        } catch (HttpClientErrorException e) {
            // 무효·만료 토큰은 401(resultcode 024)로 온다.
            throw new UnauthorizedException(Messages.get("auth.naver.access-token-invalid"), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(Messages.get("auth.naver.verification-failed"), e);
        }
        if (profile == null
                || !NAVER_SUCCESS.equals(profile.path("resultcode").asText(""))
                || profile.path("response").path("id").asText("").isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.naver.access-token-invalid"));
        }
        return SocialAuthService.toResult(PROVIDER, profile);
    }
}
