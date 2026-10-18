package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SocialAuthServiceTest {
    private MockRestServiceServer server;
    private SocialAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OAuthProperties properties = new OAuthProperties(
                new OAuthProperties.Provider("kakao-key", "kakao-secret", "https://kauth.test/oauth/token", "https://kapi.test/v2/user/me"),
                new OAuthProperties.Provider("naver-id", "naver-secret", "https://nid.test/oauth2.0/token", "https://openapi.test/v1/nid/me"),
                new OAuthProperties.Provider("google-id", "google-secret", "https://google.test/token", "https://google.test/userinfo")
        );
        service = new SocialAuthService(builder, properties);
    }

    @Test
    void exchangesKakaoCodeForAppCompatibleUserId() {
        server.expect(requestTo("https://kauth.test/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "authorization_code",
                        "client_id", "kakao-key",
                        "code", "auth-code"
                )))
                .andRespond(withSuccess("{\"access_token\":\"kakao-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.test/v2/user/me"))
                .andExpect(header("Authorization", "Bearer kakao-token"))
                .andRespond(withSuccess("{\"id\":12345,\"properties\":{\"nickname\":\"림\"}}", MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("kakao", "auth-code", "http://localhost:5173");

        assertThat(result.userId()).isEqualTo("kakao:12345");
        assertThat(result.displayName()).isEqualTo("림");
    }

    @Test
    void exchangesNaverCodeForAppCompatibleUserId() {
        server.expect(requestTo("https://nid.test/oauth2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"naver-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openapi.test/v1/nid/me"))
                .andExpect(header("Authorization", "Bearer naver-token"))
                .andRespond(withSuccess("{\"response\":{\"id\":\"nv-88\",\"name\":\"임성규\"}}", MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("naver", "auth-code", "http://localhost:5173");

        assertThat(result.userId()).isEqualTo("naver:nv-88");
        assertThat(result.displayName()).isEqualTo("임성규");
    }

    @Test
    void exchangesGoogleCodeUsingOidcSubject() {
        server.expect(requestTo("https://google.test/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"google-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://google.test/userinfo"))
                .andExpect(header("Authorization", "Bearer google-token"))
                .andRespond(withSuccess("{\"sub\":\"109876\",\"name\":\"SK Lim\"}", MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("google", "auth-code", "http://localhost:5173");

        assertThat(result.userId()).isEqualTo("google:109876");
        assertThat(result.displayName()).isEqualTo("SK Lim");
    }

    @Test
    void rejectsProfilesWithoutIdentifierInsteadOfSharedUserId() {
        for (String[] c : new String[][]{
                {"kakao", "https://kauth.test/oauth/token", "https://kapi.test/v2/user/me", "{\"properties\":{}}"},
                {"naver", "https://nid.test/oauth2.0/token", "https://openapi.test/v1/nid/me", "{\"response\":{}}"},
                {"google", "https://google.test/token", "https://google.test/userinfo", "{\"name\":\"x\"}"}}) {
            server.reset();
            server.expect(requestTo(c[1]))
                    .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(c[2])).andRespond(withSuccess(c[3], MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> service.authenticate(c[0], "auth-code", "http://localhost:5173"))
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessageContaining(c[0]);
        }
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.authenticate("apple", "code", "http://localhost:5173"))
                .withMessageContaining("apple");
    }

    @Test
    void rejectsProviderWithoutServerConfiguration() {
        OAuthProperties empty = new OAuthProperties(null, null, null);
        SocialAuthService unconfigured = new SocialAuthService(RestClient.builder(), empty);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.authenticate("kakao", "code", "http://localhost:5173"))
                .withMessage("이 로그인 방식은 지금 사용할 수 없어요.")
                .withMessageNotContaining("OAUTH_");
    }
}
