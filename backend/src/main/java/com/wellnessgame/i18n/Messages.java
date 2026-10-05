package com.wellnessgame.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 사용자에게 보이는 문구를 현재 요청 로케일(ko 기본, en 지원)로 만든다.
 *
 * <p>문구는 {@code messages.properties}(ko) / {@code messages_en.properties}(en) 에 있다.
 * 같은 {@link MessageSource} 인스턴스를 스프링 빈({@code messageSource})으로도 등록해 Bean Validation 메시지
 * 보간에 함께 쓴다. 정적 접근을 두는 이유는 도메인 예외를 던지는 곳(검증기, 인증 서비스 등)이 DI 없이
 * 단위 테스트로도 생성되기 때문이다.
 *
 * <p>로케일은 {@link LocaleContextHolder} 에서 얻는다. 요청 밖(서비스 단위 테스트 등)이거나 영어가 아니면 ko 다.
 * 숫자 인자는 {@link java.text.MessageFormat} 규칙으로 서식화되므로 자릿수 구분이 필요 없는 곳은
 * properties 에서 {@code {0,number,#}} 를 쓴다. 인자가 있는 문구의 작은따옴표는 {@code ''} 로 써야 하므로
 * 영어 문구의 축약형은 ’(U+2019) 를 쓴다.
 */
public final class Messages {
    public static final Locale DEFAULT_LOCALE = Locale.KOREAN;
    public static final List<Locale> SUPPORTED_LOCALES = List.of(Locale.KOREAN, Locale.ENGLISH);
    public static final String BASENAME = "messages";

    private static final ResourceBundleMessageSource SOURCE = createSource();

    private Messages() {
    }

    /** 스프링 컨텍스트에 등록할 공용 MessageSource. */
    public static MessageSource source() {
        return SOURCE;
    }

    public static String get(String code, Object... args) {
        return SOURCE.getMessage(code, args, currentLocale());
    }

    public static String get(LocalizedMessage message) {
        return get(message.code(), message.args());
    }

    /** 현재 요청 로케일을 지원 로케일로 좁힌 값. 요청 밖이면 ko. */
    public static Locale currentLocale() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        if (locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage())) {
            return Locale.ENGLISH;
        }
        return DEFAULT_LOCALE;
    }

    private static ResourceBundleMessageSource createSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(BASENAME);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // 서버 OS 로케일과 무관하게 messages_en 이 없으면 기본 파일(ko)로 떨어지게 한다.
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
