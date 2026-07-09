package com.wellnessgame.api;

import com.wellnessgame.auth.SocialAuthService;
import com.wellnessgame.auth.SocialLoginResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final SocialAuthService authService;

    public AuthController(SocialAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/{provider}")
    public SocialLoginResponse login(@PathVariable String provider, @Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResult result = authService.authenticate(provider, request.code(), request.redirectUri());
        return new SocialLoginResponse(result.userId(), result.displayName());
    }

    public record SocialLoginRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    public record SocialLoginResponse(String userId, String displayName) {
    }
}
