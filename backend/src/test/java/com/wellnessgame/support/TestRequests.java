package com.wellnessgame.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MockMvc 요청 빌더 헬퍼.
 */
public final class TestRequests {
    private TestRequests() {
    }

    /** 요청의 연결 주소(remoteAddr)를 지정한다. */
    public static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }
}
