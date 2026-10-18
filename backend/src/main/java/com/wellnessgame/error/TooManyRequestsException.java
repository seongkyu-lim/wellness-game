package com.wellnessgame.error;

/**
 * 사용자에게 그대로 보여 줄 429 오류. 응답에 {@code Retry-After}(초) 헤더를 붙인다. 메시지는 번역된 문구여야 한다.
 */
public class TooManyRequestsException extends RuntimeException {
    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
