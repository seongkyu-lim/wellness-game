package com.wellnessgame.auth;

/**
 * 발급된 액세스 토큰. expiresIn 은 초 단위 유효 기간이다.
 */
public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    public static final String BEARER = "Bearer";
}
