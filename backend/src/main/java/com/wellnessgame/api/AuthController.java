package com.wellnessgame.api;

import com.wellnessgame.auth.AppleNativeAuthService;
import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.IssuedToken;
import com.wellnessgame.auth.JwtTokenService;
import com.wellnessgame.auth.KakaoNativeAuthService;
import com.wellnessgame.auth.NaverNativeAuthService;
import com.wellnessgame.auth.PasswordAuthResult;
import com.wellnessgame.auth.PasswordAuthService;
import com.wellnessgame.auth.SocialAuthService;
import com.wellnessgame.auth.SocialLoginResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인·회원가입. 모든 응답은 공통 토큰 필드(userId, displayName, accessToken, tokenType, expiresIn)를 담는다.
 * accessToken 의 sub 는 응답 userId 와 같다.
 *
 * <p>계정 정책: userId 는 "{provider}:{provider 식별자}" 라서 로그인 방식별로 별도 계정이다. 같은 사람이
 * 카카오·네이버·구글·애플·아이디 로그인으로 각각 가입해도 이메일 등으로 계정을 합치지 않는다.
 * iOS 네이티브 토큰 교환({@code /{provider}/native})은 웹 코드 교환과 같은 userId 규칙을 써서 같은 계정으로 이어진다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final SocialAuthService authService;
    private final PasswordAuthService passwordAuthService;
    private final GoogleNativeAuthService googleNativeAuthService;
    private final KakaoNativeAuthService kakaoNativeAuthService;
    private final NaverNativeAuthService naverNativeAuthService;
    private final AppleNativeAuthService appleNativeAuthService;
    private final JwtTokenService tokenService;

    public AuthController(
            SocialAuthService authService,
            PasswordAuthService passwordAuthService,
            GoogleNativeAuthService googleNativeAuthService,
            KakaoNativeAuthService kakaoNativeAuthService,
            NaverNativeAuthService naverNativeAuthService,
            AppleNativeAuthService appleNativeAuthService,
            JwtTokenService tokenService
    ) {
        this.authService = authService;
        this.passwordAuthService = passwordAuthService;
        this.googleNativeAuthService = googleNativeAuthService;
        this.kakaoNativeAuthService = kakaoNativeAuthService;
        this.naverNativeAuthService = naverNativeAuthService;
        this.appleNativeAuthService = appleNativeAuthService;
        this.tokenService = tokenService;
    }

    @PostMapping("/signup")
    public PasswordAuthResponse signUp(@Valid @RequestBody SignUpRequest request) {
        PasswordAuthResult result = passwordAuthService.signUp(request.username(), request.password(), request.displayName());
        return PasswordAuthResponse.of(result, tokenService.issue(result.userId()));
    }

    @PostMapping("/login")
    public PasswordAuthResponse logIn(@Valid @RequestBody LoginRequest request) {
        PasswordAuthResult result = passwordAuthService.signIn(request.username(), request.password());
        return PasswordAuthResponse.of(result, tokenService.issue(result.userId()));
    }

    /** iOS Google Sign-In ID 토큰 교환. 리터럴 경로라 {@code /{provider}} 보다 우선한다. */
    @PostMapping("/google/native")
    public SocialLoginResponse googleNative(@Valid @RequestBody GoogleNativeLoginRequest request) {
        return SocialLoginResponse.of(googleNativeAuthService.authenticate(request.idToken()), tokenService);
    }

    /** iOS 카카오 SDK 액세스 토큰 교환. 토큰의 app_id 가 이 앱인지 확인한다. */
    @PostMapping("/kakao/native")
    public SocialLoginResponse kakaoNative(@Valid @RequestBody AccessTokenLoginRequest request) {
        return SocialLoginResponse.of(kakaoNativeAuthService.authenticate(request.accessToken()), tokenService);
    }

    /** iOS 네이버 SDK 액세스 토큰 교환. 앱 소유 검증 한계는 {@link NaverNativeAuthService} 참고. */
    @PostMapping("/naver/native")
    public SocialLoginResponse naverNative(@Valid @RequestBody AccessTokenLoginRequest request) {
        return SocialLoginResponse.of(naverNativeAuthService.authenticate(request.accessToken()), tokenService);
    }

    /** iOS Sign in with Apple identity token 교환. fullName 은 Apple 이 최초 로그인 때만 주는 이름이다. */
    @PostMapping("/apple/native")
    public SocialLoginResponse appleNative(@Valid @RequestBody AppleNativeLoginRequest request) {
        return SocialLoginResponse.of(
                appleNativeAuthService.authenticate(request.identityToken(), request.fullName()), tokenService);
    }

    @PostMapping("/{provider}")
    public SocialLoginResponse login(@PathVariable String provider, @Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResult result = authService.authenticate(provider, request.code(), request.redirectUri());
        return SocialLoginResponse.of(result, tokenService);
    }

    public record SignUpRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "{validation.username.pattern}") String username,
            @NotBlank @Size(min = 8, max = 72, message = "{validation.password.size}") String password,
            String displayName
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
            long expiresIn
    ) {
        static PasswordAuthResponse of(PasswordAuthResult result, IssuedToken token) {
            return new PasswordAuthResponse(result.userIdentifier(), result.userId(), result.displayName(),
                    token.accessToken(), token.tokenType(), token.expiresIn());
        }
    }

    public record SocialLoginRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    public record GoogleNativeLoginRequest(@NotBlank String idToken) {
    }

    public record AccessTokenLoginRequest(@NotBlank String accessToken) {
    }

    public record AppleNativeLoginRequest(@NotBlank String identityToken, @Size(max = 100) String fullName) {
    }

    public record SocialLoginResponse(
            String userId,
            String displayName,
            String accessToken,
            String tokenType,
            long expiresIn
    ) {
        static SocialLoginResponse of(SocialLoginResult result, JwtTokenService tokenService) {
            IssuedToken token = tokenService.issue(result.userId());
            return new SocialLoginResponse(result.userId(), result.displayName(),
                    token.accessToken(), token.tokenType(), token.expiresIn());
        }
    }
}
