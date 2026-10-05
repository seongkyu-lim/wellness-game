package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleNativeAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String TOKENINFO = "https://google.test/tokeninfo";
    private static final String IOS_CLIENT = "ios-client.apps.googleusercontent.com";
    private static final String WEB_CLIENT = "web-client.apps.googleusercontent.com";

    private MockRestServiceServer server;
    private GoogleNativeAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoogleNativeAuthService(
                builder,
                oauth(WEB_CLIENT),
                new GoogleNativeProperties(List.of(IOS_CLIENT), TOKENINFO),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void verifiesIosIdTokenAndUsesGoogleSubjectUserId() {
        expectTokeninfo(tokeninfo(IOS_CLIENT, NOW.plusSeconds(3600)));

        SocialLoginResult result = service.authenticate("id-token");

        assertThat(result.userId()).isEqualTo("google:109876");
        assertThat(result.displayName()).isEqualTo("SK Lim");
        server.verify();
    }

    @Test
    void acceptsWebClientIdAudience() {
        expectTokeninfo(tokeninfo(WEB_CLIENT, NOW.plusSeconds(3600)));

        assertThat(service.authenticate("id-token").userId()).isEqualTo("google:109876");
    }

    @Test
    void rejectsAudienceMismatch() {
        expectTokeninfo(tokeninfo("someone-else.apps.googleusercontent.com", NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.authenticate("id-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("구글 ID 토큰");
    }

    @Test
    void rejectsExpiredIdToken() {
        expectTokeninfo(tokeninfo(IOS_CLIENT, NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.authenticate("id-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsIdTokenExpiringExactlyNow() {
        expectTokeninfo(tokeninfo(IOS_CLIENT, NOW));

        assertThatThrownBy(() -> service.authenticate("id-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsUnexpectedIssuer() {
        expectTokeninfo(tokeninfo(IOS_CLIENT, NOW.plusSeconds(3600)).replace("https://accounts.google.com", "https://evil.test"));

        assertThatThrownBy(() -> service.authenticate("id-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsTokenThatGoogleReportsInvalid() {
        server.expect(requestTo(TOKENINFO + "?id_token=bad-token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_token\"}"));

        assertThatThrownBy(() -> service.authenticate("bad-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reportsUnavailableWhenGoogleFails() {
        server.expect(requestTo(TOKENINFO + "?id_token=id-token")).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("id-token"));
    }

    @Test
    void reportsUnavailableWhenNoClientIdConfigured() {
        GoogleNativeAuthService unconfigured = new GoogleNativeAuthService(
                RestClient.builder(),
                new OAuthProperties(null, null, null),
                new GoogleNativeProperties(null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatIllegalStateException().isThrownBy(() -> unconfigured.authenticate("id-token"));
    }

    private void expectTokeninfo(String body) {
        server.expect(requestTo(TOKENINFO + "?id_token=id-token"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String tokeninfo(String aud, Instant exp) {
        return """
                {"iss":"https://accounts.google.com","aud":"%s","sub":"109876","name":"SK Lim",
                 "email":"sk@example.com","iat":"%d","exp":"%d","alg":"RS256"}
                """.formatted(aud, exp.minusSeconds(3600).getEpochSecond(), exp.getEpochSecond());
    }

    private static OAuthProperties oauth(String webClientId) {
        return new OAuthProperties(null, null,
                new OAuthProperties.Provider(webClientId, "secret", "https://google.test/token", "https://google.test/userinfo"));
    }
}
