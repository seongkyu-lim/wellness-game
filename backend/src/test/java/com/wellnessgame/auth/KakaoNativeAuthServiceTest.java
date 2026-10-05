package com.wellnessgame.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoNativeAuthServiceTest {
    private static final String TOKEN_INFO = "https://kakao.test/v1/user/access_token_info";
    private static final String PROFILE = "https://kakao.test/v2/user/me";
    private static final String APP_ID = "1234567";

    private MockRestServiceServer server;
    private KakaoNativeAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new KakaoNativeAuthService(builder, new SocialAuthService(builder, oauth()),
                new KakaoNativeProperties(APP_ID, TOKEN_INFO));
    }

    @Test
    void verifiesAppIdThenUsesWebProfileRules() {
        expectTokenInfo("""
                {"id": 4242, "expires_in": 7199, "app_id": 1234567}
                """);
        server.expect(requestTo(PROFILE))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-token"))
                .andRespond(withSuccess("""
                        {"id": 4242, "properties": {"nickname": "카카오친구"}}
                        """, MediaType.APPLICATION_JSON));

        SocialLoginResult result = service.authenticate("kakao-token");

        assertThat(result.userId()).isEqualTo("kakao:4242");
        assertThat(result.displayName()).isEqualTo("카카오친구");
        server.verify();
    }

    @Test
    void rejectsTokenIssuedForAnotherAppWithoutCallingProfile() {
        expectTokenInfo("""
                {"id": 4242, "expires_in": 7199, "app_id": 999}
                """);

        assertThatThrownBy(() -> service.authenticate("kakao-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이 앱에 발급된 카카오 토큰이 아닙니다.");
        server.verify();
    }

    @Test
    void rejectsExpiredOrUnknownTokenReportedByKakao() {
        server.expect(requestTo(TOKEN_INFO))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"msg\":\"this access token does not exist\",\"code\":-401}"));

        assertThatThrownBy(() -> service.authenticate("expired-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void rejectsTokenWithoutRemainingLifetime() {
        expectTokenInfo("""
                {"id": 4242, "expires_in": 0, "app_id": 1234567}
                """);

        assertThatThrownBy(() -> service.authenticate("kakao-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsProfileOfDifferentUser() {
        expectTokenInfo("""
                {"id": 4242, "expires_in": 7199, "app_id": 1234567}
                """);
        server.expect(requestTo(PROFILE)).andRespond(withSuccess("""
                {"id": 1, "properties": {"nickname": "other"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("kakao-token")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reportsUnavailableWhenKakaoFails() {
        server.expect(requestTo(TOKEN_INFO)).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("kakao-token"));
    }

    @Test
    void reportsUnavailableWhenProfileFails() {
        expectTokenInfo("""
                {"id": 4242, "expires_in": 7199, "app_id": 1234567}
                """);
        server.expect(requestTo(PROFILE)).andRespond(withServerError());

        assertThatIllegalStateException().isThrownBy(() -> service.authenticate("kakao-token"));
    }

    @Test
    void reportsUnavailableWithoutLeakingSettingNameWhenAppIdMissing() {
        KakaoNativeAuthService unconfigured = new KakaoNativeAuthService(RestClient.builder(),
                new SocialAuthService(RestClient.builder(), oauth()), new KakaoNativeProperties(" ", null));

        assertThatIllegalStateException()
                .isThrownBy(() -> unconfigured.authenticate("kakao-token"))
                .withMessage("카카오 로그인을 사용할 수 없습니다.");
    }

    private void expectTokenInfo(String body) {
        server.expect(requestTo(TOKEN_INFO))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static OAuthProperties oauth() {
        // 웹 client-id 가 없어도 네이티브 로그인은 profile-uri 만으로 동작한다.
        return new OAuthProperties(
                new OAuthProperties.Provider(null, null, "https://kakao.test/oauth/token", PROFILE), null, null);
    }
}
