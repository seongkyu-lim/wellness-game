package com.wellnessgame.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 경로별 CSP 선택과 오류 디스패치 처리를 필터 단위로 확인한다. */
class SecurityHeadersFilterPathTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private MockHttpServletResponse run(String uri, String contextPath, DispatcherType type) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + uri);
        request.setContextPath(contextPath);
        request.setDispatcherType(type);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void swaggerUiPathsGetTheRelaxedSameOriginPolicy() throws Exception {
        for (String uri : new String[]{"/swagger-ui/index.html", "/swagger-ui/swagger-ui.css", "/swagger-ui.html"}) {
            assertThat(run(uri, "", DispatcherType.REQUEST).getHeader("Content-Security-Policy"))
                    .as(uri).isEqualTo(SecurityHeadersFilter.SWAGGER_CSP);
        }
    }

    @Test
    void otherPathsGetFrameAncestorsOnlyPolicy() throws Exception {
        for (String uri : new String[]{"/api/characters/me", "/swagger-ui", "/swagger-ui.html.bak/x", "/v3/api-docs", "/"}) {
            MockHttpServletResponse response = run(uri, "", DispatcherType.REQUEST);
            assertThat(response.getHeader("Content-Security-Policy")).as(uri).isEqualTo(SecurityHeadersFilter.API_CSP);
        }
    }

    @Test
    void contextPathIsExcludedFromPathMatching() throws Exception {
        assertThat(run("/swagger-ui/index.html", "/app", DispatcherType.REQUEST).getHeader("Content-Security-Policy"))
                .isEqualTo(SecurityHeadersFilter.SWAGGER_CSP);
    }

    @Test
    void errorDispatchIsFilteredToo() throws Exception {
        assertThat(filter.shouldNotFilterErrorDispatch()).isFalse();
        MockHttpServletResponse response = run("/error", "", DispatcherType.ERROR);

        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo(SecurityHeadersFilter.HSTS);
    }
}
