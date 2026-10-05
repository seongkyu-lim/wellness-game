package com.wellnessgame.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.JwtTokenService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
