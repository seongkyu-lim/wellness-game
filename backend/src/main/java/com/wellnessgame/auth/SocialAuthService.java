package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import com.fasterxml.jackson.databind.JsonNode;
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
    private static final Set<String> SUPPORTED = Set.of("kakao", "naver", "google");

    private final RestClient restClient;
    private final OAuthProperties properties;

    public SocialAuthService(RestClient.Builder restClientBuilder, OAuthProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public SocialLoginResult authenticate(String providerName, String code, String redirectUri) {
        if (!SUPPORTED.contains(providerName)) {
            throw new IllegalArgumentException(Messages.get("auth.social.unsupported-provider", providerName));
        }
        OAuthProperties.Provider provider = switch (providerName) {
            case "kakao" -> properties.kakao();
            case "naver" -> properties.naver();
            default -> properties.google();
        };
        if (provider == null || !provider.configured()) {
            throw new IllegalStateException(
                    Messages.get("auth.social.not-configured", providerName, providerName.toUpperCase()));
        }

        try {
            String accessToken = requestAccessToken(provider, code, redirectUri);
            JsonNode profile = restClient.get()
                    .uri(provider.profileUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            return toResult(providerName, profile);
        } catch (RestClientException e) {
            throw new IllegalStateException(Messages.get("auth.social.failed", providerName), e);
        }
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
            throw new IllegalStateException(Messages.get("auth.social.token-issue-failed"));
        }
        return accessToken;
    }

    private SocialLoginResult toResult(String providerName, JsonNode profile) {
        return switch (providerName) {
            // iOS: user.id (회원번호)
            case "kakao" -> new SocialLoginResult(
                    "kakao:" + profile.path("id").asLong(),
                    profile.path("properties").path("nickname").asText(null));
            // iOS: profile["id"]
            case "naver" -> {
                JsonNode response = profile.path("response");
                String name = response.path("name").asText(response.path("nickname").asText(null));
                yield new SocialLoginResult("naver:" + response.path("id").asText(), name);
            }
            // iOS: GIDGoogleUser.userID == OIDC sub
            default -> new SocialLoginResult(
                    "google:" + profile.path("sub").asText(),
                    profile.path("name").asText(null));
        };
    }
}
