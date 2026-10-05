package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.AppleNativeAuthService;
import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.KakaoNativeAuthService;
import com.wellnessgame.auth.NaverNativeAuthService;
import com.wellnessgame.auth.SocialAuthService;
import com.wellnessgame.auth.SocialLoginResult;
import com.wellnessgame.auth.UnauthorizedException;
import com.wellnessgame.auth.UserCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.wellnessgame.support.TestRequests.fromIp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService tokenService;

    @Autowired
    private UserCredentialRepository credentialRepository;

    @MockitoSpyBean
    private SocialAuthService socialAuthService;

    @MockitoSpyBean
    private GoogleNativeAuthService googleNativeAuthService;

    @MockitoSpyBean
    private KakaoNativeAuthService kakaoNativeAuthService;

    @MockitoSpyBean
    private NaverNativeAuthService naverNativeAuthService;

    @MockitoSpyBean
    private AppleNativeAuthService appleNativeAuthService;

    @BeforeEach
    void cleanDatabase() {
        credentialRepository.deleteAll();
    }

    @Test
    void rejectsUnsupportedProvider() throws Exception {
        mockMvc.perform(post("/api/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsServiceUnavailableWhenProviderNotConfigured() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void rejectsBlankCode() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---- 토큰 발급 (#43) ----

    @Test
    void signUpReturnsAccessTokenForPasswordUserId() throws Exception {
        JsonNode body = postJson("/api/auth/signup", """
                {"username": "alice_01", "password": "password1", "displayName": "Alice"}
                """);

        String identifier = body.path("userIdentifier").asText();
        assertThat(identifier).isNotBlank();
        assertThat(body.path("userId").asText()).isEqualTo("password:" + identifier);
        assertThat(body.path("displayName").asText()).isEqualTo("Alice");
        assertTokenFields(body, "password:" + identifier);
    }

    @Test
    void loginReturnsAccessTokenWithSameUserId() throws Exception {
        JsonNode signedUp = postJson("/api/auth/signup", """
                {"username": "bob_01", "password": "password1"}
                """);

        JsonNode loggedIn = postJson("/api/auth/login", """
                {"username": "bob_01", "password": "password1"}
                """);

        assertThat(loggedIn.path("userIdentifier").asText()).isEqualTo(signedUp.path("userIdentifier").asText());
        assertThat(loggedIn.path("userId").asText()).isEqualTo(signedUp.path("userId").asText());
        assertThat(loggedIn.path("displayName").asText()).isEqualTo("bob_01");
        assertTokenFields(loggedIn, signedUp.path("userId").asText());
    }

    @Test
    void loginFailureKeepsBadRequestWithoutToken() throws Exception {
        postJson("/api/auth/signup", """
                {"username": "carol_01", "password": "password1"}
                """);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "carol_01", "password": "wrong-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("아이디 또는 비밀번호가 올바르지 않습니다.")))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    /** #55 잠금은 (아이디, IP) 단위라서 다른 IP 의 정상 로그인은 막지 않는다. */
    @Test
    void loginLockoutIsPerUsernameAndClientIp() throws Exception {
        postJson("/api/auth/signup", """
                {"username": "erin_01", "password": "password1"}
                """);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(fromIp(post("/api/auth/login"), "203.0.113.77")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\": \"erin_01\", \"password\": \"wrong-password\"}"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(fromIp(post("/api/auth/login"), "203.0.113.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"erin_01\", \"password\": \"password1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("아이디 또는 비밀번호가 올바르지 않습니다.")));
        mockMvc.perform(fromIp(post("/api/auth/login"), "198.51.100.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"erin_01\", \"password\": \"password1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void socialCodeExchangeReturnsAccessToken() throws Exception {
        doReturn(new SocialLoginResult("google:109876", "SK Lim"))
                .when(socialAuthService).authenticate("google", "auth-code", "http://localhost:5173/");

        JsonNode body = postJson("/api/auth/google", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("google:109876");
        assertThat(body.path("displayName").asText()).isEqualTo("SK Lim");
        assertTokenFields(body, "google:109876");
    }

    @Test
    void googleNativeReturnsAccessTokenForGoogleSubject() throws Exception {
        doReturn(new SocialLoginResult("google:109876", "SK Lim"))
                .when(googleNativeAuthService).authenticate("ios-id-token");

        JsonNode body = postJson("/api/auth/google/native", """
                {"idToken": "ios-id-token"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("google:109876");
        assertThat(body.path("displayName").asText()).isEqualTo("SK Lim");
        assertTokenFields(body, "google:109876");
    }

    @Test
    void googleNativeRejectsInvalidIdTokenWithUnauthorized() throws Exception {
        doThrow(new UnauthorizedException("이 앱에 발급된 구글 ID 토큰이 아닙니다."))
                .when(googleNativeAuthService).authenticate("foreign-token");

        mockMvc.perform(post("/api/auth/google/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken": "foreign-token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void googleNativeRejectsBlankIdToken() throws Exception {
        mockMvc.perform(post("/api/auth/google/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void googleNativeIsUnavailableWhenClientIdsNotConfigured() throws Exception {
        mockMvc.perform(post("/api/auth/google/native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken": "any-token"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    // ---- iOS 네이티브 카카오·네이버·애플 (#53) ----

    @Test
    void kakaoNativeReturnsAccessTokenAndIsNotRoutedToCodeExchange() throws Exception {
        doReturn(new SocialLoginResult("kakao:4242", "카카오친구"))
                .when(kakaoNativeAuthService).authenticate("kakao-token");

        JsonNode body = postJson("/api/auth/kakao/native", """
                {"accessToken": "kakao-token"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("kakao:4242");
        assertThat(body.path("displayName").asText()).isEqualTo("카카오친구");
        assertTokenFields(body, "kakao:4242");
        verify(socialAuthService, never()).authenticate(anyString(), any(), any());
    }

    @Test
    void naverNativeReturnsAccessToken() throws Exception {
        doReturn(new SocialLoginResult("naver:nv-abc", "네이버친구"))
                .when(naverNativeAuthService).authenticate("naver-token", "naver-refresh");

        JsonNode body = postJson("/api/auth/naver/native", """
                {"accessToken": "naver-token", "refreshToken": "naver-refresh"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("naver:nv-abc");
        assertTokenFields(body, "naver:nv-abc");
        verify(socialAuthService, never()).authenticate(anyString(), any(), any());
    }

    @Test
    void appleNativeReturnsAccessTokenForAppleSubject() throws Exception {
        doReturn(new SocialLoginResult("apple:001234.abc", "임성규"))
                .when(appleNativeAuthService).authenticate("apple-identity-token", "임성규");

        JsonNode body = postJson("/api/auth/apple/native", """
                {"identityToken": "apple-identity-token", "fullName": "임성규"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("apple:001234.abc");
        assertThat(body.path("displayName").asText()).isEqualTo("임성규");
        assertTokenFields(body, "apple:001234.abc");
    }

    @Test
    void appleNativeAcceptsMissingFullName() throws Exception {
        doReturn(new SocialLoginResult("apple:001234.abc", "Apple 사용자"))
                .when(appleNativeAuthService).authenticate("apple-identity-token", null);

        JsonNode body = postJson("/api/auth/apple/native", """
                {"identityToken": "apple-identity-token"}
                """);

        assertTokenFields(body, "apple:001234.abc");
    }

    @Test
    void codeExchangeStillRoutesSingleSegmentProvider() throws Exception {
        doReturn(new SocialLoginResult("kakao:4242", "카카오친구"))
                .when(socialAuthService).authenticate("kakao", "auth-code", "http://localhost:5173/");

        JsonNode body = postJson("/api/auth/kakao", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);

        assertTokenFields(body, "kakao:4242");
        verify(kakaoNativeAuthService, never()).authenticate(anyString());
    }

    @Test
    void nativeEndpointsMapVerificationFailureToUnauthorized() throws Exception {
        doThrow(new UnauthorizedException("x")).when(kakaoNativeAuthService).authenticate("bad");
        doThrow(new UnauthorizedException("x")).when(naverNativeAuthService).authenticate("bad", "bad");
        doThrow(new UnauthorizedException("x")).when(appleNativeAuthService).authenticate("bad", null);

        for (String[] request : new String[][]{
                {"/api/auth/kakao/native", "{\"accessToken\": \"bad\"}"},
                {"/api/auth/naver/native", "{\"accessToken\": \"bad\", \"refreshToken\": \"bad\"}"},
                {"/api/auth/apple/native", "{\"identityToken\": \"bad\"}"}}) {
            mockMvc.perform(post(request[0]).contentType(MediaType.APPLICATION_JSON).content(request[1]))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.accessToken").doesNotExist());
        }
    }

    @Test
    void nativeEndpointsRejectBlankTokens() throws Exception {
        for (String[] request : new String[][]{
                {"/api/auth/kakao/native", "{\"accessToken\": \"\"}"},
                {"/api/auth/naver/native", "{}"},
                {"/api/auth/naver/native", "{\"accessToken\": \"a\"}"},
                {"/api/auth/naver/native", "{\"accessToken\": \"a\", \"refreshToken\": \"\"}"},
                {"/api/auth/apple/native", "{\"identityToken\": \" \"}"}}) {
            mockMvc.perform(post(request[0]).contentType(MediaType.APPLICATION_JSON).content(request[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void nativeEndpointsRejectOverlongTokens() throws Exception {
        String longToken = "a".repeat(4097);
        for (String[] request : new String[][]{
                {"/api/auth/google/native", "{\"idToken\": \"" + longToken + "\"}"},
                {"/api/auth/kakao/native", "{\"accessToken\": \"" + longToken + "\"}"},
                {"/api/auth/naver/native", "{\"accessToken\": \"a\", \"refreshToken\": \"" + longToken + "\"}"},
                {"/api/auth/apple/native", "{\"identityToken\": \"" + longToken + "\"}"}}) {
            mockMvc.perform(post(request[0]).contentType(MediaType.APPLICATION_JSON).content(request[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("4096")));
        }
    }

    @Test
    void appleNativeAcceptsOverlongFullNameForServerSideTruncation() throws Exception {
        String longName = "가".repeat(150);
        doReturn(new SocialLoginResult("apple:001234.abc", "가".repeat(100)))
                .when(appleNativeAuthService).authenticate("apple-identity-token", longName);

        JsonNode body = postJson("/api/auth/apple/native",
                "{\"identityToken\": \"apple-identity-token\", \"fullName\": \"" + longName + "\"}");

        assertTokenFields(body, "apple:001234.abc");
    }

    /** 테스트 설정에는 OAUTH_KAKAO_NATIVE_APP_ID·OAUTH_NAVER_CLIENT_ID·OAUTH_APPLE_BUNDLE_ID 가 없다. */
    @Test
    void nativeEndpointsArePublicAndUnavailableWhenNotConfigured() throws Exception {
        for (String[] request : new String[][]{
                {"/api/auth/kakao/native", "{\"accessToken\": \"any\"}"},
                {"/api/auth/naver/native", "{\"accessToken\": \"any\", \"refreshToken\": \"any\"}"},
                {"/api/auth/apple/native", "{\"identityToken\": \"any\"}"}}) {
            mockMvc.perform(post(request[0])
                            .header("Authorization", "Bearer garbage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request[1]))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message", not(org.hamcrest.Matchers.containsString("OAUTH_"))));
        }
    }

    @Test
    void authEndpointsArePublicEvenWithGarbageAuthorizationHeader() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .header("Authorization", "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "dave_01", "password": "password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())));
    }

    private JsonNode postJson(String path, String json) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void assertTokenFields(JsonNode body, String expectedSubject) {
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(2_592_000L);
        String accessToken = body.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokenService.verify(accessToken)).isEqualTo(expectedSubject);
    }
}
