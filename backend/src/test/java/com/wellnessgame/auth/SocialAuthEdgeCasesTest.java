package com.wellnessgame.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 웹 코드 교환·네이티브 로그인 서비스의 오류 경로와 프로필 변환 경계값. */
class SocialAuthEdgeCasesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERIFIER = "v".repeat(43);

    private MockRestServiceServer server;
    private SocialAuthService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new SocialAuthService(builder, new OAuthProperties(
                new OAuthProperties.Provider("kakao-key", null, "https://kauth.test/token", "https://kapi.test/me"),
                new OAuthProperties.Provider("naver-id", "naver-secret", "https://nid.test/token", null),
                new OAuthProperties.Provider(" ", "x", "https://g.test/token", "https://g.test/me")));
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ---- 토큰 교환 응답 ----

    @Test
    void tokenResponseWithoutAccessTokenIsServiceUnavailable() {
        server.expect(requestTo("https://kauth.test/token"))
                .andRespond(withSuccess("{\"error\":\"invalid_grant\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.authenticate("kakao", "code", "http://localhost", VERIFIER))
                .isInstanceOf(ServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void tokenEndpointFailureIsServiceUnavailableAndWrapsCause() {
        server.expect(requestTo("https://kauth.test/token")).andRespond(withServerError());

        assertThatThrownBy(() -> service.authenticate("kakao", "code", "http://localhost", VERIFIER))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasCauseInstanceOf(org.springframework.web.client.RestClientException.class);
    }

    @Test
    void profileEndpointFailureIsServiceUnavailable() {
        server.expect(requestTo("https://kauth.test/token"))
                .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.test/me")).andRespond(withServerError());

        assertThatThrownBy(() -> service.authenticate("kakao", "code", "http://localhost", VERIFIER))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void blankClientIdProviderIsNotConfigured() {
        assertThatThrownBy(() -> service.authenticate("google", "code", "http://localhost", VERIFIER))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void providerNameIsCaseSensitive() {
        assertThatThrownBy(() -> service.authenticate("KAKAO", "code", "http://localhost", VERIFIER))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- fetchProfile ----

    @Test
    void fetchProfileRejectsUnknownProviderAndMissingProfileUri() {
        assertThatThrownBy(() -> service.fetchProfile("apple", "t")).isInstanceOf(BadRequestException.class);
        // 네이버는 profileUri 가 비어 있음
        assertThatThrownBy(() -> service.fetchProfile("naver", "t")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void fetchProfileCallsProfileUriWithBearerToken() {
        server.expect(requestTo("https://kapi.test/me"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("Authorization", "Bearer abc"))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));

        assertThat(service.fetchProfile("kakao", "abc").path("id").asLong()).isEqualTo(7);
        server.verify();
    }

    // ---- 프로필 -> userId 변환 ----

    @Test
    void kakaoIdMustBePositive() {
        assertThat(SocialAuthService.toResult("kakao", json("{\"id\":1}")).userId()).isEqualTo("kakao:1");
        for (String body : new String[]{"{\"id\":0}", "{\"id\":-5}", "{}", "{\"id\":\"abc\"}"}) {
            assertThatThrownBy(() -> SocialAuthService.toResult("kakao", json(body)))
                    .as(body).isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void nullProfileIsUnauthorizedForEveryProvider() {
        for (String provider : new String[]{"kakao", "naver", "google"}) {
            assertThatThrownBy(() -> SocialAuthService.toResult(provider, null))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void naverFallsBackToNicknameWhenNameMissing() {
        SocialLoginResult byName = SocialAuthService.toResult("naver",
                json("{\"response\":{\"id\":\"n1\",\"name\":\"이름\",\"nickname\":\"별명\"}}"));
        SocialLoginResult byNickname = SocialAuthService.toResult("naver",
                json("{\"response\":{\"id\":\"n1\",\"nickname\":\"별명\"}}"));
        SocialLoginResult noName = SocialAuthService.toResult("naver", json("{\"response\":{\"id\":\"n1\"}}"));

        assertThat(byName.displayName()).isEqualTo("이름");
        assertThat(byNickname.displayName()).isEqualTo("별명");
        assertThat(noName.displayName()).isNull();
    }

    @Test
    void googleSubjectMustBeNonBlank() {
        assertThat(SocialAuthService.toResult("google", json("{\"sub\":\"s1\",\"name\":\"N\"}")).userId())
                .isEqualTo("google:s1");
        assertThatThrownBy(() -> SocialAuthService.toResult("google", json("{\"sub\":\"  \"}")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> SocialAuthService.toResult("google", json("{\"name\":\"N\"}")))
                .isInstanceOf(UnauthorizedException.class);
    }
}
