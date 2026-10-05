package com.wellnessgame.error;

/**
 * 사용자에게 그대로 보여 줄 400 오류. 메시지는 {@link com.wellnessgame.i18n.Messages} 로 번역된 문구여야 한다.
 *
 * <p>기존 호출부·테스트와의 호환을 위해 {@link IllegalArgumentException} 을 상속한다. 응답 핸들러는 이 타입의 메시지만
 * 노출하고, 그 밖의 {@link IllegalArgumentException} 은 일반 문구로 응답한다.
 */
public class BadRequestException extends IllegalArgumentException {
    public BadRequestException(String message) {
        super(message);
    }
}
