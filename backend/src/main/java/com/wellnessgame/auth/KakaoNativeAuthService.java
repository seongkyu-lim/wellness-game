package com.wellnessgame.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * iOS 카카오 SDK 가 발급한 액세스 토큰을 검증해 웹 코드 교환({@link SocialAuthService})과 같은
 * "kakao:{회원번호}" userId 를 만든다.
 *
 * <ol>
 *   <li>access_token_info 로 토큰 유효성과 발급 앱(app_id)을 확인한다. 다른 앱에 발급된 토큰은 거부한다.</li>
 *   <li>웹과 같은 profile 호출·userId 규칙·displayName 추출을 재사용한다.</li>
 *   <li>두 응답의 회원번호가 다르면 거부한다.</li>
 * </ol>
 */
@Service
public class KakaoNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(KakaoNativeAuthService.class);
    private static final String PROVIDER = "kakao";

    private final RestClient restClient;
    private final SocialAuthService socialAuthService;
    private final KakaoNativeProperties properties;

    public KakaoNativeAuthService(
            RestClient.Builder restClientBuilder,
            SocialAuthService socialAuthService,
            KakaoNativeProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.socialAuthService = socialAuthService;
        this.properties = properties;
    }

    public SocialLoginResult authenticate(String accessToken) {
        if (!properties.configured()) {
            log.error("카카오 네이티브 로그인 설정 없음: OAUTH_KAKAO_NATIVE_APP_ID 환경변수를 확인하세요.");
            throw new IllegalStateException(Messages.get("auth.kakao.unavailable"));
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth.native.access-token-blank"));
        }

        try {
            JsonNode tokenInfo = restClient.get()
                    .uri(properties.accessTokenInfoUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            long tokenUserId = verifiedUserId(tokenInfo);

            JsonNode profile = socialAuthService.fetchProfile(PROVIDER, accessToken);
            if (profile == null || profile.path("id").asLong() != tokenUserId) {
                throw new UnauthorizedException(Messages.get("auth.kakao.access-token-invalid"));
            }
            return SocialAuthService.toResult(PROVIDER, profile);
        } catch (HttpClientErrorException e) {
            // 카카오는 무효·만료 토큰에 401(code -401)을 준다.
            throw new UnauthorizedException(Messages.get("auth.kakao.access-token-invalid"), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(Messages.get("auth.kakao.verification-failed"), e);
        }
    }

    private long verifiedUserId(JsonNode tokenInfo) {
        if (tokenInfo == null || tokenInfo.path("id").asLong() <= 0 || tokenInfo.path("expires_in").asLong() <= 0) {
            throw new UnauthorizedException(Messages.get("auth.kakao.access-token-invalid"));
        }
        if (!properties.nativeAppId().equals(tokenInfo.path("app_id").asText(""))) {
            throw new UnauthorizedException(Messages.get("auth.kakao.wrong-app"));
        }
        return tokenInfo.path("id").asLong();
    }
}
