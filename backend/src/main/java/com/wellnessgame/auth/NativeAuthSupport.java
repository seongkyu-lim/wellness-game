package com.wellnessgame.auth;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import org.slf4j.Logger;

/**
 * iOS 네이티브 토큰 교환 서비스(구글·카카오·네이버·애플)의 공통 전처리.
 * 설정 이름은 서버 로그에만 남기고 응답 문구에는 노출하지 않는다.
 */
final class NativeAuthSupport {
    private NativeAuthSupport() {
    }

    /** 미설정(503). 호출부에서 {@code throw NativeAuthSupport.unavailable(...)} 로 쓴다. */
    static ServiceUnavailableException unavailable(Logger log, String provider, String settingHint, String messageCode) {
        log.error("{} 네이티브 로그인 설정 없음: {} 를 확인하세요.", provider, settingHint);
        return new ServiceUnavailableException(Messages.get(messageCode));
    }

    /** 빈 토큰(400). */
    static void requireToken(String token, String messageCode) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException(Messages.get(messageCode));
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
