package com.wellnessgame.config;

import com.wellnessgame.i18n.Messages;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * 응답 문구 다국어 처리. Accept-Language 가 ko 또는 en 이면 그 언어로, 없거나 다른 언어면 ko 로 응답한다.
 */
@Configuration
public class I18nConfig {
    /** 빈 이름이 messageSource 여야 스프링 부트 기본 MessageSource 대신 쓰이고 Bean Validation 보간에도 연결된다. */
    @Bean
    public MessageSource messageSource() {
        return Messages.source();
    }

    /** 빈 이름이 localeResolver 여야 DispatcherServlet 이 사용한다. */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(Messages.SUPPORTED_LOCALES);
        resolver.setDefaultLocale(Messages.DEFAULT_LOCALE);
        return resolver;
    }
}
