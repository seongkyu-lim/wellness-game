package com.wellnessgame.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 레이트 리밋·로그인 잠금 키로 쓸 클라이언트 IP.
 *
 * <p>기본은 {@link HttpServletRequest#getRemoteAddr()} 다. {@code X-Forwarded-For} 는 클라이언트가 임의로 넣을 수 있어
 * {@code app.rate-limit.trust-forwarded-header} 가 켜졌을 때만 본다. 이때도 헤더가 여러 줄이면 마지막 줄의 마지막 값,
 * 즉 바로 앞단의 신뢰하는 프록시가 덧붙인 주소를 쓴다(프록시 한 단 전제). 그 값이 64자를 넘거나 IP 리터럴이 아니면
 * 연결 주소로 돌아간다. DNS 조회는 하지 않는다.
 *
 * <p>IPv6 는 /64 접두사로 묶는다. 한 가입자가 보통 /64 전체를 받으므로 주소를 바꿔 가며 키를 늘리는 것을 막는다.
 */
@Component
public class ClientIpResolver {
    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final int MAX_ADDRESS_LENGTH = 64;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    private final boolean trustForwardedHeader;

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustForwardedHeader = properties.trustForwardedHeader();
    }

    public String resolve(HttpServletRequest request) {
        return resolve(request, trustForwardedHeader);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader) {
        if (trustForwardedHeader) {
            String forwarded = normalize(lastForwardedAddress(request));
            if (forwarded != null) {
                return forwarded;
            }
        }
        String remote = request.getRemoteAddr();
        String normalized = normalize(remote);
        if (normalized != null) {
            return normalized;
        }
        return remote == null || remote.isBlank() ? "unknown" : truncate(remote);
    }

    /** IP 리터럴이면 키 형식(IPv4 그대로, IPv6 는 /64)으로, 아니면 null. */
    static String normalize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String value = candidate.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > MAX_ADDRESS_LENGTH) {
            return null;
        }
        if (IPV4.matcher(value).matches()) {
            return validIpv4(value) ? value : null;
        }
        if (!value.contains(":") || !IPV6_CHARS.matcher(value).matches()) {
            return null;
        }
        try {
            // 대괄호로 감싸면 JDK 가 IPv6 리터럴로만 해석한다(호스트 이름 조회 없음).
            InetAddress address = InetAddress.getByName("[" + value + "]");
            return address instanceof Inet6Address ? ipv6Prefix64(address.getAddress()) : address.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean validIpv4(String value) {
        for (String octet : value.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String ipv6Prefix64(byte[] bytes) {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
        }
        return prefix.append(":/64").toString();
    }

    private static String lastForwardedAddress(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        List<String> lines = headers == null ? List.of() : Collections.list(headers);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line != null && !line.isBlank()) {
                String[] parts = line.split(",");
                return parts.length == 0 ? null : parts[parts.length - 1];
            }
        }
        return null;
    }

    private static String truncate(String value) {
        return value.length() > MAX_ADDRESS_LENGTH ? value.substring(0, MAX_ADDRESS_LENGTH) : value;
    }
}
