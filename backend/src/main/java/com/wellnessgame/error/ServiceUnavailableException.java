package com.wellnessgame.error;

/**
 * 사용자에게 그대로 보여 줄 503 오류(서버 설정 누락, 외부 인증 서버 장애 등). 메시지는 번역된 문구여야 하며,
 * 원인(cause)·설정 이름 같은 상세는 서버 로그에만 남긴다.
 *
 * <p>기존 호출부·테스트와의 호환을 위해 {@link IllegalStateException} 을 상속한다. 응답 핸들러는 이 타입의 메시지만
 * 노출하고, 그 밖의 {@link IllegalStateException} 은 일반 문구로 응답한다.
 */
public class ServiceUnavailableException extends IllegalStateException {
    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
