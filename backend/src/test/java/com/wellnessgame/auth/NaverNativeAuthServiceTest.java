package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NaverNativeAuthServiceTest {
    private static final String PROFILE = "https://naver.test/v1/nid/me";

    private MockRestServiceServer server;
    private NaverNativeAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OAuthProperties oauth = oauth("naver-client");
        service = new NaverNativeAuthService(new SocialAuthService(builder, oauth), oauth);
    }

    @Test
    void acceptsTokenWhenProfileSucceeds() {
        server.expect(requestTo(PROFILE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer naver-token"))
                .andRespond(withSuccess("""
                        {"resultcode":"00","message":"success","response":{"id":"nv-abc","name":"네이버친구"}}
                        """, MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("naver-token");

        assertThat(result.userId()).isEqualTo("naver:nv-abc");
        assertThat(result.displayName()).isEqualTo("네이버친구");
        server.verify();
    }

    @Test
    void rejectsExpiredTokenReportedByNaver() {
        server.expect(requestTo(PROFILE)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}"));

        assertThatThrownBy(() -> service.authenticate("naver-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsUnsuccessfulResultCodeOrMissingId() {
        server.expect(requestTo(PROFILE)).andRespond(withSuccess("""
                {"resultcode":"00","message":"success","response":{}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("naver-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reportsUnavailableWhenNaverFails() {
        server.expect(requestTo(PROFILE)).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("naver-token"));
    }

    @Test
    void reportsUnavailableWhenClientIdMissing() {
        OAuthProperties oauth = oauth("");
        NaverNativeAuthService unconfigured = new NaverNativeAuthService(
                new SocialAuthService(RestClient.builder(), oauth), oauth);

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.authenticate("naver-token"))
                .withMessage("네이버 로그인을 사용할 수 없습니다.");
    }

    private static OAuthProperties oauth(String clientId) {
        return new OAuthProperties(null,
                new OAuthProperties.Provider(clientId, "secret", "https://naver.test/token", PROFILE), null);
    }
}
