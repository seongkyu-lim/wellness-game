package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.AccessTokenPrincipal;
import com.wellnessgame.auth.AppleNativeAuthService;
import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.KakaoNativeAuthService;
import com.wellnessgame.auth.NaverNativeAuthService;
import com.wellnessgame.auth.SocialAuthService;
import com.wellnessgame.auth.SocialLoginResult;
import com.wellnessgame.auth.TokenScope;
import com.wellnessgame.auth.UnauthorizedException;
import com.wellnessgame.auth.UserCredential;
import com.wellnessgame.auth.UserCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static com.wellnessgame.support.TestAuth.bearer;
import static com.wellnessgame.support.TestAuth.bearerToken;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private PasswordEncoder passwordEncoder;

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
                        .with(bearer(tokenService, "guest:any"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** 공개 경로는 지원 공급자 코드 교환만이다. 그 밖의 /api/auth/* 는 토큰이 필요하다(조용히 공개되지 않게). */
    @Test
    void unknownAuthPathRequiresBearer() throws Exception {
        mockMvc.perform(post("/api/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "auth-code", "redirectUri": "http://localhost:5173"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    /** Auth v2 하위 호환: 기존 필드는 그대로 두고 refreshToken·refreshExpiresIn 만 더한다. */
    @Test
    void loginResponsesKeepExistingFieldsAndAddRefreshFields() throws Exception {
        JsonNode password = postJson("/api/auth/signup", """
                {"username": "compat_01", "password": "password1"}
                """);
        assertThat(fieldNames(password)).containsExactlyInAnyOrder("userIdentifier", "userId", "displayName",
                "accessToken", "tokenType", "expiresIn", "refreshToken", "refreshExpiresIn");

        doReturn(new SocialLoginResult("kakao:4242", "카카오친구"))
                .when(kakaoNativeAuthService).authenticate("kakao-token");
        JsonNode social = postJson("/api/auth/kakao/native", """
                {"accessToken": "kakao-token"}
                """);
        assertThat(fieldNames(social)).containsExactlyInAnyOrder("userId", "displayName",
                "accessToken", "tokenType", "expiresIn", "refreshToken", "refreshExpiresIn");
    }

    @Test
    void appleNativePassesNonceToVerification() throws Exception {
        doReturn(new SocialLoginResult("apple:001234.abc", "Apple 사용자"))
                .when(appleNativeAuthService).authenticate("apple-identity-token", null, "raw-nonce");

        JsonNode body = postJson("/api/auth/apple/native", """
                {"identityToken": "apple-identity-token", "nonce": "raw-nonce"}
                """);

        assertTokenFields(body, "apple:001234.abc");
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
                .when(socialAuthService).authenticate("google", "auth-code", "http://localhost:5173/", null);

        JsonNode body = postJson("/api/auth/google", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);

        assertThat(body.path("userId").asText()).isEqualTo("google:109876");
        assertThat(body.path("displayName").asText()).isEqualTo("SK Lim");
        assertReadSessionFields(body, "google:109876");
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
        verify(socialAuthService, never()).authenticate(anyString(), any(), any(), any());
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
        verify(socialAuthService, never()).authenticate(anyString(), any(), any(), any());
    }

    @Test
    void appleNativeReturnsAccessTokenForAppleSubject() throws Exception {
        doReturn(new SocialLoginResult("apple:001234.abc", "임성규"))
                .when(appleNativeAuthService).authenticate("apple-identity-token", "임성규", null);

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
                .when(appleNativeAuthService).authenticate("apple-identity-token", null, null);

        JsonNode body = postJson("/api/auth/apple/native", """
                {"identityToken": "apple-identity-token"}
                """);

        assertTokenFields(body, "apple:001234.abc");
    }

    @Test
    void codeExchangeStillRoutesSingleSegmentProvider() throws Exception {
        doReturn(new SocialLoginResult("kakao:4242", "카카오친구"))
                .when(socialAuthService).authenticate("kakao", "auth-code", "http://localhost:5173/", null);

        JsonNode body = postJson("/api/auth/kakao", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);

        assertReadSessionFields(body, "kakao:4242");
        verify(kakaoNativeAuthService, never()).authenticate(anyString());
    }

    @Test
    void nativeEndpointsMapVerificationFailureToUnauthorized() throws Exception {
        doThrow(new UnauthorizedException("x")).when(kakaoNativeAuthService).authenticate("bad");
        doThrow(new UnauthorizedException("x")).when(naverNativeAuthService).authenticate("bad", "bad");
        doThrow(new UnauthorizedException("x")).when(appleNativeAuthService).authenticate("bad", null, null);

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
                .when(appleNativeAuthService).authenticate("apple-identity-token", longName, null);

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

    /** #106 가입 검증: 닉네임 64자, 비밀번호 UTF-8 72바이트. */
    @Test
    void signUpRejectsTooLongDisplayNameAndPasswordOver72Utf8Bytes() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"long_name\", \"password\": \"password1\", \"displayName\": \""
                                + "가".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("닉네임은 64자 이하")));

        // 25자지만 75바이트(한글 3바이트). 글자 수 제한(72자)은 통과하고 바이트 제한에 걸린다.
        mockMvc.perform(post("/api/auth/signup")
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"long_pw\", \"password\": \"" + "가".repeat(25) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("at most 72 bytes")));

        // 경계값: 정확히 64자 닉네임, 72바이트 비밀번호(한글 24자)는 통과한다.
        postJson("/api/auth/signup", "{\"username\": \"edge_ok\", \"password\": \"" + "가".repeat(24)
                + "\", \"displayName\": \"" + "a".repeat(64) + "\"}");
    }

    /** #106 아이디는 소문자로 저장하고, 대소문자와 무관하게 로그인된다. */
    @Test
    void usernameIsStoredLowercaseAndLoginIsCaseInsensitive() throws Exception {
        JsonNode signedUp = postJson("/api/auth/signup", """
                {"username": "Mixed_Case", "password": "password1"}
                """);
        assertThat(credentialRepository.findByUsername("mixed_case")).isPresent();
        assertThat(signedUp.path("displayName").asText()).isEqualTo("mixed_case");

        JsonNode login = postJson("/api/auth/login", """
                {"username": "MIXED_case", "password": "password1"}
                """);
        assertThat(login.path("userId").asText()).isEqualTo(signedUp.path("userId").asText());

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"mixed_CASE\", \"password\": \"password1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("이미 사용 중인 아이디입니다.")));
    }

    /** 소문자 정규화 이전에 대소문자를 섞어 가입한 계정도 로그인되고, 같은 아이디로 새로 가입할 수 없다. */
    @Test
    void legacyMixedCaseAccountCanStillLogIn() throws Exception {
        credentialRepository.save(new UserCredential("Legacy_User", passwordEncoder.encode("password1"),
                "legacy-identifier", "Legacy"));

        for (String typed : new String[]{"Legacy_User", "legacy_user", "LEGACY_USER"}) {
            JsonNode login = postJson("/api/auth/login",
                    "{\"username\": \"" + typed + "\", \"password\": \"password1\"}");
            assertThat(login.path("userIdentifier").asText()).as(typed).isEqualTo("legacy-identifier");
        }
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"legacy_user\", \"password\": \"password1\"}"))
                .andExpect(status().isBadRequest());
    }

    /** #105 웹 코드 교환: 조회 전용 세션이고, 회전해도 조회 전용으로만 다시 발급된다. */
    @Test
    void webCodeExchangeIssuesReadScopeSessionThatStaysReadOnlyAcrossRefresh() throws Exception {
        doReturn(new SocialLoginResult("google:read-1", "Reader"))
                .when(socialAuthService).authenticate("google", "auth-code", "http://localhost:5173/", null);
        JsonNode login = postJson("/api/auth/google", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);
        assertReadOnlyAccess(login.path("accessToken").asText());

        JsonNode refreshed = postJson("/api/auth/refresh",
                "{\"refreshToken\": \"" + login.path("refreshToken").asText() + "\"}");
        assertThat(refreshed.path("refreshExpiresIn").asLong()).isEqualTo(Duration.ofDays(7).toSeconds());
        assertReadOnlyAccess(refreshed.path("accessToken").asText());
    }

    /** #103 웹(조회 전용) family 로그아웃은 같은 사용자의 Watch 토큰을 끊지 않는다(앱 로그아웃만 끊음). */
    @Test
    void webLogoutDoesNotInvalidateWatchTokens() throws Exception {
        doReturn(new SocialLoginResult("google:web-logout", "Web"))
                .when(socialAuthService).authenticate("google", "auth-code", "http://localhost:5173/", null);
        String watch = tokenService.issueReadOnly("google:web-logout").accessToken();
        JsonNode web = postJson("/api/auth/google", """
                {"code": "auth-code", "redirectUri": "http://localhost:5173/"}
                """);

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + web.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/characters/me").with(bearerToken(watch)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + web.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** 앱 로그인(아이디·네이티브)은 계속 전체 권한이다. */
    @Test
    void appLoginStillIssuesFullScope() throws Exception {
        JsonNode body = postJson("/api/auth/signup", """
                {"username": "full_scope_01", "password": "password1"}
                """);
        assertThat(tokenService.authenticate(body.path("accessToken").asText()).scope()).isEqualTo(TokenScope.FULL);
    }

    @Test
    void webCodeExchangePassesValidCodeVerifierAndRejectsMalformedOne() throws Exception {
        String verifier = "aZ09-._~".repeat(6); // 48자, RFC 7636 허용 문자만
        doReturn(new SocialLoginResult("kakao:pkce", "PKCE"))
                .when(socialAuthService).authenticate("kakao", "auth-code", "http://localhost:5173/", verifier);
        postJson("/api/auth/kakao",
                "{\"code\": \"auth-code\", \"redirectUri\": \"http://localhost:5173/\", \"codeVerifier\": \"" + verifier + "\"}");
        verify(socialAuthService).authenticate("kakao", "auth-code", "http://localhost:5173/", verifier);

        for (String bad : new String[]{"short", "a".repeat(129), "a".repeat(42) + "+", "a".repeat(43) + " "}) {
            mockMvc.perform(post("/api/auth/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\": \"c\", \"redirectUri\": \"http://localhost:5173/\", \"codeVerifier\": \""
                                    + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("codeVerifier")));
        }
    }

    private void assertReadOnlyAccess(String accessToken) throws Exception {
        assertThat(tokenService.authenticate(accessToken).scope()).isEqualTo(TokenScope.READ);
        mockMvc.perform(get("/api/characters/me").with(bearerToken(accessToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/health-activities/sync").with(bearerToken(accessToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/watch-token").with(bearerToken(accessToken)))
                .andExpect(status().isForbidden());
    }

    /** 웹 조회 전용 세션: 액세스 토큰 수명은 앱과 같고, 리프레시는 조회 전용 수명(7일)이다. */
    private void assertReadSessionFields(JsonNode body, String expectedSubject) {
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(tokenService.ttl().toSeconds());
        AccessTokenPrincipal principal = tokenService.authenticate(body.path("accessToken").asText());
        assertThat(principal.userId()).isEqualTo(expectedSubject);
        assertThat(principal.scope()).isEqualTo(TokenScope.READ);
        assertThat(body.path("refreshToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.path("refreshExpiresIn").asLong()).isEqualTo(Duration.ofDays(7).toSeconds());
    }

    private void assertTokenFields(JsonNode body, String expectedSubject) {
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(2_592_000L);
        String accessToken = body.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(tokenService.verify(accessToken)).isEqualTo(expectedSubject);
        assertThat(body.path("refreshToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.path("refreshExpiresIn").asLong()).isEqualTo(2_592_000L);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
