package com.wellnessgame.auth;

import java.time.Instant;

/** 검증된 액세스 토큰의 주체(sub = userId), 권한 범위, 발급 시각(iat, 없으면 null). */
public record AccessTokenPrincipal(String userId, TokenScope scope, Instant issuedAt) {
}
