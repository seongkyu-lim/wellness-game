package com.wellnessgame.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccessAndResolverTest {

    @Test
    void userAccessFallsBackToAuthenticatedUserWhenRequestedIsEmpty() {
        assertThat(UserAccess.resolve("me", null)).isEqualTo("me");
        assertThat(UserAccess.resolve("me", "")).isEqualTo("me");
        assertThat(UserAccess.resolve("me", "   ")).isEqualTo("me");
    }

    @Test
    void userAccessAcceptsSameUserAndRejectsOthers() {
        assertThat(UserAccess.resolve("me", "me")).isEqualTo("me");
        assertThatThrownBy(() -> UserAccess.resolve("me", "someone-else")).isInstanceOf(ForbiddenException.class);
        // 공백이 섞인 값은 같은 사용자로 보지 않는다(정확히 일치만 허용).
        assertThatThrownBy(() -> UserAccess.resolve("me", " me")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> UserAccess.resolve("me", "ME")).isInstanceOf(ForbiddenException.class);
    }

    @SuppressWarnings("unused")
    void handler(@AuthenticatedUser String user, @AuthenticatedUser Integer wrongType, String plain) {
    }

    private static MethodParameter param(int index) throws Exception {
        return new MethodParameter(UserAccessAndResolverTest.class.getDeclaredMethod(
                "handler", String.class, Integer.class, String.class), index);
    }

    @Test
    void resolverSupportsOnlyAnnotatedStringParameters() throws Exception {
        AuthenticatedUserArgumentResolver resolver = new AuthenticatedUserArgumentResolver();

        assertThat(resolver.supportsParameter(param(0))).isTrue();
        assertThat(resolver.supportsParameter(param(1))).isFalse();
        assertThat(resolver.supportsParameter(param(2))).isFalse();
    }

    @Test
    void resolverReturnsPrincipalOrRejectsMissingAndBlank() throws Exception {
        AuthenticatedUserArgumentResolver resolver = new AuthenticatedUserArgumentResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        ServletWebRequest web = new ServletWebRequest(request);

        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, web, null))
                .isInstanceOf(UnauthorizedException.class);
        request.setAttribute(AuthenticationInterceptor.USER_ID_ATTRIBUTE, " ");
        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, web, null))
                .isInstanceOf(UnauthorizedException.class);
        request.setAttribute(AuthenticationInterceptor.USER_ID_ATTRIBUTE, 42); // 문자열이 아닌 값
        assertThatThrownBy(() -> resolver.resolveArgument(param(0), null, web, null))
                .isInstanceOf(UnauthorizedException.class);
        request.setAttribute(AuthenticationInterceptor.USER_ID_ATTRIBUTE, "user-1");
        assertThat(resolver.resolveArgument(param(0), null, web, null)).isEqualTo("user-1");
    }
}
