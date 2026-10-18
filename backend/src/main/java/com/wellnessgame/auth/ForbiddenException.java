package com.wellnessgame.auth;

/**
 * 인증은 됐지만 요청한 자원이 토큰 주체의 것이 아님. HTTP 403 으로 응답한다.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
