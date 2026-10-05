package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NaverNativeAuthServiceTest {
    private static final String TOKEN = "https://naver.test/oauth2.0/token";
    private static final String PROFILE = "https://naver.test/v1/nid/me";

    private MockRestServiceServer server;
    private NaverNativeAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = service(builder, oauth("naver-client", "naver-secret"));
    }

    @Test
    void refreshesWithServerCredentialsAndUsesOnlyTheNewAccessToken() {
        server.expect(requestTo(TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", "naver-client",
                        "client_secret", "naver-secret",
                        "refresh_token", "app-refresh")))
                .andRespond(withSuccess("""
                        {"access_token":"server-access","token_type":"bearer","expires_in":"3600"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROFILE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer server-access"))
                .andRespond(withSuccess("""
                        {"resultcode":"00","message":"success","response":{"id":"nv-abc","name":"네이버친구"}}
                        """, MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("app-access", "app-refresh");

        assertThat(result.userId()).isEqualTo("naver:nv-abc");
        assertThat(result.displayName()).isEqualTo("네이버친구");
        server.verify();
    }

    @Test
    void rejectsRefreshTokenOfAnotherAppReportedAsClientError() {
        server.expect(requestTo(TOKEN)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> service.authenticate("app-access", "foreign-refresh"))
                .isInstanceOf(UnauthorizedException.class);
        server.verify(); // 프로필은 조회하지 않는다
    }

    @Test
    void rejectsRefreshTokenOfAnotherAppReportedAsErrorBody() {
        server.expect(requestTo(TOKEN)).andRespond(withSuccess("""
                {"error":"invalid_request","error_description":"invalid refresh_token"}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("app-access", "foreign-refresh"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("네이버");
        server.verify();
    }

    @Test
    void rejectsUnsuccessfulProfileResultCode() {
        expectRefreshOk();
        server.expect(requestTo(PROFILE)).andRespond(withSuccess("""
                {"resultcode":"024","message":"Authentication failed"}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("app-access", "app-refresh"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsProfileWithoutId() {
        expectRefreshOk();
        server.expect(requestTo(PROFILE)).andRespond(withSuccess("""
                {"resultcode":"00","message":"success","response":{}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("app-access", "app-refresh"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reportsUnavailableWhenNaverTokenEndpointFails() {
        server.expect(requestTo(TOKEN)).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("app-access", "app-refresh"));
    }

    @Test
    void reportsUnavailableWhenNaverProfileFails() {
        expectRefreshOk();
        server.expect(requestTo(PROFILE)).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("app-access", "app-refresh"));
    }

    @Test
    void rejectsMissingRefreshToken() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.authenticate("app-access", " "))
                .withMessage("refreshToken이 비어 있습니다.");
    }

    @Test
    void reportsUnavailableWhenClientIdOrSecretMissing() {
        for (OAuthProperties oauth : new OAuthProperties[]{oauth("", "naver-secret"), oauth("naver-client", "")}) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> service(RestClient.builder(), oauth).authenticate("app-access", "app-refresh"))
                    .withMessage("네이버 로그인을 사용할 수 없습니다.");
        }
    }

    private void expectRefreshOk() {
        server.expect(requestTo(TOKEN)).andRespond(withSuccess("""
                {"access_token":"server-access","token_type":"bearer","expires_in":"3600"}
                """, MediaType.APPLICATION_JSON));
    }

    private static NaverNativeAuthService service(RestClient.Builder builder, OAuthProperties oauth) {
        return new NaverNativeAuthService(builder, new SocialAuthService(builder, oauth), oauth);
    }

    private static OAuthProperties oauth(String clientId, String clientSecret) {
        return new OAuthProperties(null, new OAuthProperties.Provider(clientId, clientSecret, TOKEN, PROFILE), null);
    }
}
