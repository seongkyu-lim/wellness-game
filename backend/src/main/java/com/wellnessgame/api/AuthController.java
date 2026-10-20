package com.wellnessgame.api;

import com.wellnessgame.auth.AppleNativeAuthService;
import com.wellnessgame.auth.AuthenticatedUser;
import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.IssuedRefreshToken;
import com.wellnessgame.auth.IssuedToken;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.KakaoNativeAuthService;
import com.wellnessgame.auth.NaverNativeAuthService;
import com.wellnessgame.auth.PasswordAuthResult;
import com.wellnessgame.auth.PasswordAuthService;
import com.wellnessgame.auth.RefreshTokenService;
import com.wellnessgame.auth.SocialAuthService;
import com.wellnessgame.auth.SocialLoginResult;
import com.wellnessgame.auth.TokenScope;
import com.wellnessgame.ratelimit.ClientIpResolver;
import com.wellnessgame.validation.MaxUtf8Bytes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인·회원가입·세션. 로그인 응답은 공통 토큰 필드(userId, displayName, accessToken, tokenType, expiresIn,
 * refreshToken, refreshExpiresIn)를 담는다. accessToken 의 sub 는 응답 userId 와 같다.
 *
 * <p>Auth v2(#55·#64·#76): 짧은 액세스 토큰 + 회전 리프레시 토큰({@code /refresh}), 로그아웃({@code /logout},
 * {@code /logout-all}), Watch 전용 조회 토큰({@code /watch-token}). 공개·보호 경로 구분은 WebConfig 에 있다.
 *
 * <p>계정 정책: userId 는 "{provider}:{provider 식별자}" 라서 로그인 방식별로 별도 계정이다. 같은 사람이
 * 카카오·네이버·구글·애플·아이디 로그인으로 각각 가입해도 이메일 등으로 계정을 합치지 않는다.
 * iOS 네이티브 토큰 교환({@code /{provider}/native})은 웹 코드 교환과 같은 userId 규칙을 써서 같은 계정으로 이어진다.
 *
 * <p>웹 코드 교환({@code /{provider}}, #105)은 조회 전용(scope=read) 세션을 준다. 웹은 조회 대시보드라서 쓰기 API 가 필요 없고,
 * 리프레시 토큰 family 에 범위를 저장해 회전해도 같은 범위로만 다시 발급한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    /** 외부 토큰 길이 상한. JWT·OAuth 토큰은 이보다 훨씬 짧다. */
    static final int MAX_TOKEN_LENGTH = 4096;
    /** 서버 리프레시 토큰은 43자(base64url 32바이트)다. */
    static final int MAX_REFRESH_TOKEN_LENGTH = 256;
    static final int MAX_NONCE_LENGTH = 256;
    /** RFC 7636 code_verifier: unreserved 문자 43~128자. */
    static final String CODE_VERIFIER_PATTERN = "^[A-Za-z0-9\\-._~]{43,128}$";

    private final SocialAuthService authService;
    private final PasswordAuthService passwordAuthService;
    private final GoogleNativeAuthService googleNativeAuthService;
    private final KakaoNativeAuthService kakaoNativeAuthService;
    private final NaverNativeAuthService naverNativeAuthService;
    private final AppleNativeAuthService appleNativeAuthService;
    private final JwtTokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(
            SocialAuthService authService,
            PasswordAuthService passwordAuthService,
            GoogleNativeAuthService googleNativeAuthService,
            KakaoNativeAuthService kakaoNativeAuthService,
            NaverNativeAuthService naverNativeAuthService,
            AppleNativeAuthService appleNativeAuthService,
            JwtTokenService tokenService,
            RefreshTokenService refreshTokenService,
            ClientIpResolver clientIpResolver
    ) {
        this.authService = authService;
        this.passwordAuthService = passwordAuthService;
        this.googleNativeAuthService = googleNativeAuthService;
        this.kakaoNativeAuthService = kakaoNativeAuthService;
        this.naverNativeAuthService = naverNativeAuthService;
        this.appleNativeAuthService = appleNativeAuthService;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/signup")
    public PasswordAuthResponse signUp(@Valid @RequestBody SignUpRequest request) {
        PasswordAuthResult result = passwordAuthService.signUp(request.username(), request.password(), request.displayName());
        return PasswordAuthResponse.of(result, newSession(result.userId()));
    }

    @PostMapping("/login")
    public PasswordAuthResponse logIn(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        PasswordAuthResult result = passwordAuthService.signIn(
                request.username(), request.password(), clientIpResolver.resolve(httpRequest));
        return PasswordAuthResponse.of(result, newSession(result.userId()));
    }

    /** iOS Google Sign-In ID 토큰 교환. 리터럴 경로라 {@code /{provider}} 보다 우선한다. */
    @PostMapping("/google/native")
    public SocialLoginResponse googleNative(@Valid @RequestBody GoogleNativeLoginRequest request) {
        return socialLogin(googleNativeAuthService.authenticate(request.idToken()));
    }

    /** iOS 카카오 SDK 액세스 토큰 교환. 토큰의 app_id 가 이 앱인지 확인한다. */
    @PostMapping("/kakao/native")
    public SocialLoginResponse kakaoNative(@Valid @RequestBody AccessTokenLoginRequest request) {
        return socialLogin(kakaoNativeAuthService.authenticate(request.accessToken()));
    }

    /** iOS 네이버 SDK 토큰 교환. refreshToken 갱신으로 앱 소유를 확인한다({@link NaverNativeAuthService}). */
    @PostMapping("/naver/native")
    public SocialLoginResponse naverNative(@Valid @RequestBody NaverNativeLoginRequest request) {
        return socialLogin(naverNativeAuthService.authenticate(request.accessToken(), request.refreshToken()));
    }

    /**
     * iOS Sign in with Apple identity token 교환. fullName 은 Apple 이 최초 로그인 때만 주는 이름이다.
     * nonce 는 앱이 Apple 요청에 SHA-256 으로 넣은 값의 원문이다(#64, 선택).
     */
    @PostMapping("/apple/native")
    public SocialLoginResponse appleNative(@Valid @RequestBody AppleNativeLoginRequest request) {
        return socialLogin(
                appleNativeAuthService.authenticate(request.identityToken(), request.fullName(), request.nonce()));
    }

    /**
     * 리프레시 토큰 회전. 재사용(이미 회전된 토큰)이면 family 전체를 폐기하고 401. 공개 경로다.
     * 액세스 토큰은 family 의 범위(앱 full, 웹 read)로 발급한다.
     */
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken());
        return RefreshResponse.of(rotation.userId(), tokenService.issue(rotation.userId(), rotation.scope()),
                rotation.refreshToken());
    }

    /**
     * 이 기기 로그아웃: body 의 리프레시 토큰 family 를 폐기하고, 그 사용자의 조회 전용(Watch·웹) 액세스 토큰도 무효로 만든다(#103). 공개 경로다(토큰 소유가 곧 권한 증명이라 액세스 토큰이
     * 만료된 뒤에도 로그아웃할 수 있다). 토큰이 없거나 이미 폐기됐어도 같은 204 로 응답해 토큰 유효 여부를 드러내지 않는다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revokeFamily(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** 모든 기기 로그아웃: 사용자의 리프레시 토큰을 모두 폐기하고 이미 발급된 액세스 토큰도 무효로 만든다. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticatedUser String userId) {
        refreshTokenService.revokeAll(userId);
        return ResponseEntity.noContent().build();
    }

    /** Watch 전용 조회 토큰(scope=read, #76). 전체 권한 토큰으로만 발급된다(조회 토큰은 인터셉터가 403). */
    @PostMapping("/watch-token")
    public IssuedToken watchToken(@AuthenticatedUser String userId) {
        return tokenService.issueReadOnly(userId);
    }

    /** 웹 코드 교환 로그인. 조회 전용(scope=read) 세션을 준다(#105). codeVerifier 는 PKCE(선택, 카카오·구글만 전달). */
    @PostMapping("/{provider}")
    public SocialLoginResponse login(@PathVariable String provider, @Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResult result = authService.authenticate(
                provider, request.code(), request.redirectUri(), request.codeVerifier());
        return SocialLoginResponse.of(result, newSession(result.userId(), TokenScope.READ));
    }

    private Session newSession(String userId) {
        return newSession(userId, TokenScope.FULL);
    }

    private Session newSession(String userId, TokenScope scope) {
        return new Session(tokenService.issue(userId, scope), refreshTokenService.issue(userId, scope));
    }

    private SocialLoginResponse socialLogin(SocialLoginResult result) {
        return SocialLoginResponse.of(result, newSession(result.userId()));
    }

    /** 로그인 한 번에 발급하는 액세스·리프레시 토큰 쌍. */
    record Session(IssuedToken access, IssuedRefreshToken refresh) {
    }

    public record SignUpRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "{validation.username.pattern}") String username,
            @NotBlank @Size(min = 8, max = 72, message = "{validation.password.size}")
            @MaxUtf8Bytes(value = 72, message = "{validation.password.bytes}") String password,
            @Size(max = 64, message = "{validation.display-name.size}") String displayName
    ) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record PasswordAuthResponse(
            String userIdentifier,
            String userId,
            String displayName,
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
        static PasswordAuthResponse of(PasswordAuthResult result, Session session) {
            return new PasswordAuthResponse(result.userIdentifier(), result.userId(), result.displayName(),
                    session.access().accessToken(), session.access().tokenType(), session.access().expiresIn(),
                    session.refresh().refreshToken(), session.refresh().expiresIn());
        }
    }

    /** codeVerifier: RFC 7636 PKCE code_verifier(선택). 있으면 공급자 토큰 교환에 code_verifier 로 넘긴다. */
    public record SocialLoginRequest(
            @NotBlank String code,
            @NotBlank String redirectUri,
            @Pattern(regexp = CODE_VERIFIER_PATTERN, message = "{validation.code-verifier.pattern}") String codeVerifier
    ) {
        public SocialLoginRequest(String code, String redirectUri) {
            this(code, redirectUri, null);
        }
    }

    public record GoogleNativeLoginRequest(@NotBlank @Size(max = MAX_TOKEN_LENGTH, message = "{validation.token.size}") String idToken) {
    }

    public record AccessTokenLoginRequest(@NotBlank @Size(max = MAX_TOKEN_LENGTH, message = "{validation.token.size}") String accessToken) {
    }

    /** refreshToken 은 서버 자격 증명으로 갱신해 이 앱에 발급된 토큰인지 확인하는 데 쓴다. */
    public record NaverNativeLoginRequest(
            @NotBlank @Size(max = MAX_TOKEN_LENGTH, message = "{validation.token.size}") String accessToken,
            @NotBlank @Size(max = MAX_TOKEN_LENGTH, message = "{validation.token.size}") String refreshToken
    ) {
    }

    /** fullName 은 길이 제한 없이 받고 서버가 100자로 잘라 쓴다. nonce 는 원문(선택, #64). */
    public record AppleNativeLoginRequest(
            @NotBlank @Size(max = MAX_TOKEN_LENGTH, message = "{validation.token.size}") String identityToken,
            String fullName,
            @Size(max = MAX_NONCE_LENGTH, message = "{validation.token.size}") String nonce
    ) {
    }

    public record RefreshRequest(
            @NotBlank @Size(max = MAX_REFRESH_TOKEN_LENGTH, message = "{validation.token.size}") String refreshToken
    ) {
    }

    public record SocialLoginResponse(
            String userId,
            String displayName,
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
        static SocialLoginResponse of(SocialLoginResult result, Session session) {
            return new SocialLoginResponse(result.userId(), result.displayName(),
                    session.access().accessToken(), session.access().tokenType(), session.access().expiresIn(),
                    session.refresh().refreshToken(), session.refresh().expiresIn());
        }
    }

    public record RefreshResponse(
            String userId,
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn
    ) {
        static RefreshResponse of(String userId, IssuedToken access, IssuedRefreshToken refresh) {
            return new RefreshResponse(userId, access.accessToken(), access.tokenType(), access.expiresIn(),
                    refresh.refreshToken(), refresh.expiresIn());
        }
    }
}
