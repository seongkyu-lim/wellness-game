package com.wellnessgame.api;

import com.wellnessgame.auth.GoogleNativeAuthService;
import com.wellnessgame.auth.IssuedToken;
import com.wellnessgame.auth.JwtTokenService;
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
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final SocialAuthService authService;
    private final PasswordAuthService passwordAuthService;
    private final GoogleNativeAuthService googleNativeAuthService;
    private final JwtTokenService tokenService;

    public AuthController(
            SocialAuthService authService,
            PasswordAuthService passwordAuthService,
            GoogleNativeAuthService googleNativeAuthService,
            JwtTokenService tokenService
    ) {
        this.authService = authService;
        this.passwordAuthService = passwordAuthService;
        this.googleNativeAuthService = googleNativeAuthService;
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

    @PostMapping("/{provider}")
    public SocialLoginResponse login(@PathVariable String provider, @Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResult result = authService.authenticate(provider, request.code(), request.redirectUri());
        return SocialLoginResponse.of(result, tokenService);
    }

    public record SignUpRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "아이디는 영문·숫자·밑줄 4~32자여야 합니다.") String username,
            @NotBlank @Size(min = 8, max = 72, message = "비밀번호는 8자 이상이어야 합니다.") String password,
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
