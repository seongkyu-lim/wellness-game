package com.wellnessgame.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * iOS 네이버 SDK 가 발급한 토큰으로 웹 코드 교환과 같은 "naver:{id}" userId 를 만든다.
 *
 * <p><b>앱 소유 확인</b>: 네이버는 액세스 토큰이 어느 client_id 에 발급됐는지 알려 주는 introspection API 가 없다.
 * 액세스 토큰만으로 프로필을 조회하면 다른 네이버 앱이 같은 사용자에게서 받은 토큰도 통과한다(토큰 대체 공격).
 * 그래서 refresh token 을 이 서버의 client_id·client_secret 으로 갱신(grant_type=refresh_token)한다.
 * 갱신은 그 refresh token 을 발급받은 앱의 자격 증명으로만 성공하므로, 성공이 곧 이 앱 소유의 증거다.
 * 갱신으로 받은 새 access token 으로만 프로필을 조회하고 요청의 accessToken 은 쓰지 않는다.
 *
 * <p>남은 한계: 앱 바이너리에 client secret 이 들어 있는 iOS SDK 구조상, secret 이 유출되면 이 확인은 무력해진다.
 * 또한 갱신 때문에 앱이 가진 기존 access token 과 별개로 새 access token 이 발급된다.
 */
@Service
public class NaverNativeAuthService {
    private static final Logger log = LoggerFactory.getLogger(NaverNativeAuthService.class);
    private static final String PROVIDER = "naver";
    private static final String NAVER_SUCCESS = "00";

    private final RestClient restClient;
    private final SocialAuthService socialAuthService;
    private final OAuthProperties properties;

    public NaverNativeAuthService(
            RestClient.Builder restClientBuilder,
            SocialAuthService socialAuthService,
            OAuthProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.socialAuthService = socialAuthService;
        this.properties = properties;
    }

    /**
     * @param accessToken  iOS SDK 의 access token. 계약상 필수지만 검증에는 쓰지 않는다(위 클래스 설명).
     * @param refreshToken iOS SDK 의 refresh token. 이 서버 자격 증명으로 갱신해 앱 소유를 확인한다.
     */
    public SocialLoginResult authenticate(String accessToken, String refreshToken) {
        OAuthProperties.Provider naver = properties == null ? null : properties.naver();
        if (naver == null || !naver.configured() || NativeAuthSupport.isBlank(naver.clientSecret())
                || NativeAuthSupport.isBlank(naver.tokenUri()) || NativeAuthSupport.isBlank(naver.profileUri())) {
            throw NativeAuthSupport.unavailable(log, "네이버",
                    "OAUTH_NAVER_CLIENT_ID·OAUTH_NAVER_CLIENT_SECRET 환경변수와 oauth.naver.token-uri/profile-uri",
                    "auth.naver.unavailable");
        }
        NativeAuthSupport.requireToken(accessToken, "auth.native.access-token-blank");
        NativeAuthSupport.requireToken(refreshToken, "auth.native.refresh-token-blank");

        try {
            String ownedAccessToken = refreshWithServerCredentials(naver, refreshToken.trim());
            JsonNode profile = socialAuthService.fetchProfile(PROVIDER, ownedAccessToken);
            if (profile == null || !NAVER_SUCCESS.equals(profile.path("resultcode").asText(""))) {
                throw new UnauthorizedException(Messages.get("auth.naver.access-token-invalid"));
            }
            return SocialAuthService.toResult(PROVIDER, profile);
        } catch (HttpClientErrorException e) {
            throw new UnauthorizedException(Messages.get("auth.naver.access-token-invalid"), e);
        } catch (RestClientException e) {
            // 5xx·타임아웃·연결 실패
            throw new IllegalStateException(Messages.get("auth.naver.verification-failed"), e);
        }
    }

    private String refreshWithServerCredentials(OAuthProperties.Provider naver, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", naver.clientId());
        form.add("client_secret", naver.clientSecret());
        form.add("refresh_token", refreshToken);

        JsonNode response = restClient.post()
                .uri(naver.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        // 네이버는 다른 앱의 refresh token·잘못된 자격 증명에도 200 + {"error": ...} 로 응답할 수 있다.
        String newAccessToken = response == null ? "" : response.path("access_token").asText("");
        if (newAccessToken.isBlank() || response.hasNonNull("error")) {
            throw new UnauthorizedException(Messages.get("auth.naver.access-token-invalid"));
        }
        return newAccessToken;
    }
}
