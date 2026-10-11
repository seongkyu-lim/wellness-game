package com.wellnessgame.auth;

/** 검증된 액세스 토큰의 주체(sub = userId)와 권한 범위. */
public record AccessTokenPrincipal(String userId, TokenScope scope) {
}
