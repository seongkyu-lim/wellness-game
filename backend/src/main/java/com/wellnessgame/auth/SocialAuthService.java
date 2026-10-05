package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

/**
 * 웹 클라이언트가 받아온 authorization code를 provider 토큰·프로필로 교환해
 * iOS 앱과 동일한 "{provider}:{식별자}" 형식의 userId를 만들어 준다.
 * (네이버·카카오 토큰 교환에는 client secret이 필요해 브라우저에서 처리할 수 없다.)
 */
@Service
public class SocialAuthService {
    private static final Logger log = LoggerFactory.getLogger(SocialAuthService.class);
    private static final Set<String> SUPPORTED = Set.of("kakao", "naver", "google");

    private final RestClient restClient;
    private final OAuthProperties properties;

    public SocialAuthService(RestClient.Builder restClientBuilder, OAuthProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public SocialLoginResult authenticate(String providerName, String code, String redirectUri) {
        if (!SUPPORTED.contains(providerName)) {
            throw new BadRequestException(Messages.get("auth.social.unsupported-provider", providerName));
        }
        OAuthProperties.Provider provider = switch (providerName) {
            case "kakao" -> properties.kakao();
            case "naver" -> properties.naver();
            default -> properties.google();
        };
        if (provider == null || !provider.configured()) {
            // 환경변수 이름은 서버 로그에만 남기고, 응답에는 provider 이름도 넣지 않는 일반 문구를 쓴다.
            log.error("{} 소셜 로그인 설정 없음: OAUTH_{}_* 환경변수를 확인하세요.", providerName, providerName.toUpperCase());
            throw new ServiceUnavailableException(Messages.get("auth.social.not-configured"));
        }

        try {
            String accessToken = requestAccessToken(provider, code, redirectUri);
            return toResult(providerName, requestProfile(provider, accessToken));
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(Messages.get("auth.social.failed", providerName), e);
        }
    }

    /**
     * provider 액세스 토큰으로 profile-uri 를 호출한다. iOS 네이티브 로그인(카카오·네이버)도 같은 호출을 쓴다.
     * profile-uri 가 없으면 {@link ServiceUnavailableException}, 호출 실패는 {@link RestClientException} 그대로 던진다.
     */
    JsonNode fetchProfile(String providerName, String accessToken) {
        OAuthProperties.Provider provider = switch (providerName) {
            case "kakao" -> properties.kakao();
            case "naver" -> properties.naver();
            case "google" -> properties.google();
            default -> throw new BadRequestException(
                    Messages.get("auth.social.unsupported-provider", providerName));
        };
        if (provider == null || provider.profileUri() == null || provider.profileUri().isBlank()) {
            throw new ServiceUnavailableException(Messages.get("auth.social.failed", providerName));
        }
        return requestProfile(provider, accessToken);
    }

    private JsonNode requestProfile(OAuthProperties.Provider provider, String accessToken) {
        return restClient.get()
                .uri(provider.profileUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);
    }

    private String requestAccessToken(OAuthProperties.Provider provider, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", provider.clientId());
        if (provider.clientSecret() != null && !provider.clientSecret().isBlank()) {
            form.add("client_secret", provider.clientSecret());
        }
        form.add("redirect_uri", redirectUri);
        form.add("code", code);

        JsonNode response = restClient.post()
                .uri(provider.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        String accessToken = response == null ? null : response.path("access_token").asText(null);
        if (accessToken == null) {
            throw new ServiceUnavailableException(Messages.get("auth.social.token-issue-failed"));
        }
        return accessToken;
    }

    /**
     * provider 프로필을 iOS 앱과 같은 "{provider}:{식별자}" userId 규칙으로 변환한다.
     * 식별자가 비어 있으면 "kakao:0"·"google:" 같은 공용 userId 가 생기지 않도록 401 로 거부한다.
     */
    static SocialLoginResult toResult(String providerName, JsonNode profile) {
        JsonNode body = profile == null ? MissingNode.getInstance() : profile;
        return switch (providerName) {
            // iOS: user.id (회원번호)
            case "kakao" -> {
                long id = body.path("id").asLong();
                yield new SocialLoginResult(
                        userId(providerName, id > 0 ? Long.toString(id) : ""),
                        body.path("properties").path("nickname").asText(null));
            }
            // iOS: profile["id"]
            case "naver" -> {
                JsonNode response = body.path("response");
                String name = response.path("name").asText(response.path("nickname").asText(null));
                yield new SocialLoginResult(userId(providerName, response.path("id").asText("")), name);
            }
            // iOS: GIDGoogleUser.userID == OIDC sub
            default -> new SocialLoginResult(
                    userId(providerName, body.path("sub").asText("")),
                    body.path("name").asText(null));
        };
    }

    private static String userId(String providerName, String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.social.failed", providerName));
        }
        return providerName + ":" + providerUserId;
    }
}
