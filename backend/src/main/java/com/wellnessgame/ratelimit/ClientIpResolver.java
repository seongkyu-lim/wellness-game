package com.wellnessgame.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 레이트 리밋 키로 쓸 클라이언트 IP.
 *
 * <p>기본은 {@link HttpServletRequest#getRemoteAddr()} 다. {@code X-Forwarded-For} 는 클라이언트가 임의로 넣을 수 있어
 * {@code trustForwardedHeader} 가 켜졌을 때만 본다. 이때도 맨 앞(클라이언트가 위조 가능)이 아니라 맨 뒤 값, 즉 바로 앞단의
 * 신뢰하는 프록시가 덧붙인 주소를 쓴다. 프록시가 여러 단이면 그만큼 앞쪽을 봐야 하므로 이 구현은 프록시 한 단을 전제로 한다.
 */
public final class ClientIpResolver {
    static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader) {
        if (trustForwardedHeader) {
            String forwarded = lastForwardedAddress(request.getHeader(FORWARDED_FOR));
            if (forwarded != null) {
                return forwarded;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private static String lastForwardedAddress(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] parts = header.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }
}
