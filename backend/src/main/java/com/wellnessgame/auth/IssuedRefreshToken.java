package com.wellnessgame.auth;

/** 발급된 리프레시 토큰 원문과 초 단위 유효 기간. 원문은 응답으로만 내보내고 저장하지 않는다. */
public record IssuedRefreshToken(String refreshToken, long expiresIn) {
}
