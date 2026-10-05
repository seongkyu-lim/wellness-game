package com.wellnessgame.auth;

/**
 * 인증 실패(토큰 없음·무효·만료, 외부 ID 토큰 검증 실패). HTTP 401 + {@code WWW-Authenticate: Bearer} 로 응답한다.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
