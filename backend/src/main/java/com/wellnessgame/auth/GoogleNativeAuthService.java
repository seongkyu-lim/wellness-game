package com.wellnessgame.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * iOS Google Sign-In 이 발급한 ID 토큰을 Google tokeninfo 로 검증해
 * 웹 코드 교환({@link SocialAuthService})과 같은 "google:{sub}" userId 를 만든다.
 */
@Service
public class GoogleNativeAuthService {
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final RestClient restClient;
    private final String tokeninfoUri;
    private final Set<String> allowedAudiences;
    private final Clock clock;

    public GoogleNativeAuthService(
            RestClient.Builder restClientBuilder,
            OAuthProperties oauthProperties,
            GoogleNativeProperties nativeProperties,
            Clock clock
    ) {
        this.restClient = restClientBuilder.build();
        this.tokeninfoUri = nativeProperties.tokeninfoUri();
        this.allowedAudiences = allowedAudiences(oauthProperties, nativeProperties);
        this.clock = clock;
    }

    public SocialLoginResult authenticate(String idToken) {
        if (allowedAudiences.isEmpty()) {
            throw new IllegalStateException(
                    "구글 로그인이 서버에 설정되어 있지 않습니다. OAUTH_GOOGLE_IOS_CLIENT_ID 또는 OAUTH_GOOGLE_CLIENT_ID 환경변수를 확인하세요.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("idToken이 비어 있습니다.");
        }

        JsonNode info;
        try {
            info = restClient.get()
                    .uri(tokeninfoUri, builder -> builder.queryParam("id_token", idToken).build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new UnauthorizedException("구글 ID 토큰이 유효하지 않습니다.", e);
        } catch (RestClientException e) {
            throw new IllegalStateException("구글 ID 토큰을 검증하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        if (info == null) {
            throw new UnauthorizedException("구글 ID 토큰이 유효하지 않습니다.");
        }

        if (!allowedAudiences.contains(info.path("aud").asText(""))) {
            throw new UnauthorizedException("이 앱에 발급된 구글 ID 토큰이 아닙니다.");
        }
        if (!GOOGLE_ISSUERS.contains(info.path("iss").asText(""))) {
            throw new UnauthorizedException("구글 ID 토큰 발급자가 올바르지 않습니다.");
        }
        long exp = parseEpochSeconds(info.path("exp"));
        if (!Instant.ofEpochSecond(exp).isAfter(clock.instant())) {
            throw new UnauthorizedException("구글 ID 토큰이 만료되었습니다. 다시 로그인해 주세요.");
        }
        String sub = info.path("sub").asText("");
        if (sub.isBlank()) {
            throw new UnauthorizedException("구글 ID 토큰이 유효하지 않습니다.");
        }
        // iOS: GIDGoogleUser.userID == OIDC sub (SocialAuthService 의 웹 규칙과 동일)
        return new SocialLoginResult("google:" + sub, info.path("name").asText(null));
    }

    private static long parseEpochSeconds(JsonNode node) {
        // tokeninfo 는 exp 를 문자열로 준다.
        try {
            return Long.parseLong(node.asText(""));
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("구글 ID 토큰이 유효하지 않습니다.", e);
        }
    }

    private static Set<String> allowedAudiences(OAuthProperties oauthProperties, GoogleNativeProperties nativeProperties) {
        Set<String> audiences = new LinkedHashSet<>();
        nativeProperties.clientIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .forEach(audiences::add);
        OAuthProperties.Provider web = oauthProperties == null ? null : oauthProperties.google();
        if (web != null && web.configured()) {
            audiences.add(web.clientId().trim());
        }
        return Set.copyOf(audiences);
    }
}
