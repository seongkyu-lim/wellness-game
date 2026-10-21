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

    /** 리프레시 토큰 본문({"refreshToken": ...}). */
    public static String refreshBody(String refreshToken) {
        return "{\"refreshToken\": \"" + refreshToken + "\"}";
    }

    /** 동기화 요청 본문. userId 는 넣지 않아 토큰 주체를 쓴다. activities 는 활동 JSON 조각들. */
    public static String syncBody(String date, String... activities) {
        return "{\"date\": \"" + date + "\", \"activities\": [" + String.join(",", activities) + "]}";
    }

    /** userId 를 명시한 동기화 요청 본문. */
    public static String syncBodyFor(String userId, String date, String... activities) {
        return "{\"userId\": \"" + userId + "\", \"date\": \"" + date + "\", \"activities\": ["
                + String.join(",", activities) + "]}";
    }

    /** 걸음 수 하나만 담은 동기화 요청 본문. */
    public static String stepsSyncBody(String date, int steps) {
        return syncBody(date, "{\"type\":\"STEPS\",\"steps\":" + steps + "}");
    }
}
