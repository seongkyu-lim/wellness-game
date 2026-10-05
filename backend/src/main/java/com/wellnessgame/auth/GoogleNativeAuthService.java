package com.wellnessgame.auth;

import com.wellnessgame.i18n.Messages;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GoogleNativeAuthService.class);
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
            throw NativeAuthSupport.unavailable(log, "구글",
                    "OAUTH_GOOGLE_IOS_CLIENT_ID 또는 OAUTH_GOOGLE_CLIENT_ID 환경변수", "auth.google.unavailable");
        }
        NativeAuthSupport.requireToken(idToken, "auth.google.id-token-blank");

        JsonNode info;
        try {
            info = restClient.get()
                    .uri(tokeninfoUri, builder -> builder.queryParam("id_token", idToken).build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(Messages.get("auth.google.verification-failed"), e);
        }
        if (info == null) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"));
        }

        if (!allowedAudiences.contains(info.path("aud").asText(""))) {
            throw new UnauthorizedException(Messages.get("auth.google.wrong-audience"));
        }
        if (!GOOGLE_ISSUERS.contains(info.path("iss").asText(""))) {
            throw new UnauthorizedException(Messages.get("auth.google.wrong-issuer"));
        }
        long exp = parseEpochSeconds(info.path("exp"));
        if (!Instant.ofEpochSecond(exp).isAfter(clock.instant())) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-expired"));
        }
        String sub = info.path("sub").asText("");
        if (sub.isBlank()) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"));
        }
        // iOS: GIDGoogleUser.userID == OIDC sub (SocialAuthService 의 웹 규칙과 동일)
        return new SocialLoginResult("google:" + sub, info.path("name").asText(null));
    }

    private static long parseEpochSeconds(JsonNode node) {
        // tokeninfo 는 exp 를 문자열로 준다.
        try {
            return Long.parseLong(node.asText(""));
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(Messages.get("auth.google.id-token-invalid"), e);
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
