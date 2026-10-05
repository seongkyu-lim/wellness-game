package com.wellnessgame.api;

import com.wellnessgame.error.BadRequestException;
import com.wellnessgame.error.ServiceUnavailableException;
import com.wellnessgame.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #47 사용자용 예외만 메시지를 노출하고, 그 밖의 예외는 상태 코드를 유지한 채 일반 문구로 응답한다.
 */
class ApiExceptionHandlerTest {
    private static final String INTERNAL_DETAIL = "internal library detail: jdbc://secret-host";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(Messages.SUPPORTED_LOCALES);
        localeResolver.setDefaultLocale(Messages.DEFAULT_LOCALE);
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setLocaleResolver(localeResolver)
                .build();
    }

    @Test
    void userFacingExceptionsExposeTheirMessage() throws Exception {
        mockMvc.perform(get("/throw/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("사용자에게 보여 줄 문구"));
        mockMvc.perform(get("/throw/service-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("잠시 후 다시 시도해 주세요"));
    }

    @Test
    void otherExceptionsKeepStatusButHideDetails() throws Exception {
        mockMvc.perform(get("/throw/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청을 처리하지 못했어요."))
                .andExpect(content().string(not(containsString("secret-host"))));
        mockMvc.perform(get("/throw/illegal-state"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("요청을 처리하지 못했어요."));
        mockMvc.perform(get("/throw/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("요청을 처리하지 못했어요."))
                .andExpect(content().string(not(containsString("secret-host"))));
    }

    @Test
    void genericMessageFollowsAcceptLanguage() throws Exception {
        mockMvc.perform(get("/throw/runtime").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("We couldn’t process your request."));
    }

    @Test
    void frameworkHandledExceptionsKeepTheirStatus() throws Exception {
        mockMvc.perform(post("/throw/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/throw/number/abc"))
                .andExpect(status().isBadRequest());
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/throw/bad-request")
        void badRequest() {
            throw new BadRequestException("사용자에게 보여 줄 문구");
        }

        @GetMapping("/throw/service-unavailable")
        void serviceUnavailable() {
            throw new ServiceUnavailableException("잠시 후 다시 시도해 주세요", new RuntimeException(INTERNAL_DETAIL));
        }

        @GetMapping("/throw/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException(INTERNAL_DETAIL);
        }

        @GetMapping("/throw/illegal-state")
        void illegalState() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }

        @GetMapping("/throw/runtime")
        void runtime() {
            throw new RuntimeException(INTERNAL_DETAIL);
        }

        @PostMapping("/throw/body")
        void body(@RequestBody Map<String, Object> body) {
        }

        @GetMapping("/throw/number/{value}")
        void number(@PathVariable("value") int value) {
        }
    }
}
