package com.wellnessgame.auth;

/**
 * 아이디/비밀번호 인증 결과. 다른 API 에서 쓰는 userId(토큰 주체)는 "password:{userIdentifier}" 이다.
 */
public record PasswordAuthResult(String userIdentifier, String displayName) {
    public static final String USER_ID_PREFIX = "password:";

    public String userId() {
        return USER_ID_PREFIX + userIdentifier;
    }
}
