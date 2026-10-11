package com.wellnessgame.i18n;

/**
 * 아직 문자열로 만들지 않은 메시지(키 + 인자). 다른 문구에 합쳐질 사유처럼, 최종 응답을 만들 때 {@link #resolve()} 로
 * 현재 요청 로케일에 맞춰 해석한다.
 */
public record LocalizedMessage(String code, Object... args) {
    /** 현재 요청 로케일 문구. */
    public String resolve() {
        return Messages.get(code, args);
    }
}
