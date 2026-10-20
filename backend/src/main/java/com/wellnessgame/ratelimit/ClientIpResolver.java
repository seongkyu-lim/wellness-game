package com.wellnessgame.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 레이트 리밋·로그인 잠금 키로 쓸 클라이언트 IP.
 *
 * <p>기본은 {@link HttpServletRequest#getRemoteAddr()} 다. {@code X-Forwarded-For} 는 클라이언트가 임의로 넣을 수 있어
 * {@code app.rate-limit.trust-forwarded-header} 가 켜졌을 때만 본다. Render 등 프록시는 클라이언트가 보낸 값을 지우지 않고
 * 오른쪽에 덧붙이므로 왼쪽 값은 위조할 수 있다. 그래서 여러 줄을 순서대로 이어 붙인 목록에서 오른쪽부터
 * {@code app.rate-limit.trusted-proxy-hops} 개를 건너뛴 값(왼쪽 기준 size-1-hops 번째)을 쓴다(#102). hops=0 이면
 * 바로 앞단 프록시가 덧붙인 마지막 값이다. 그 위치가 없거나, 값이 64자를 넘거나 IP 리터럴이 아니면 연결 주소로 돌아간다.
 * DNS 조회는 하지 않는다.
 *
 * <p>{@code app.rate-limit.log-forwarded-chain} 이 켜지면 배포 뒤 hops 를 맞출 수 있게 연결 주소와 헤더 원문을
 * {@link #CHAIN_LOG_INTERVAL_MILLIS} 에 한 번만 INFO 로 남긴다.
 *
 * <p>IPv6 는 /64 접두사로 묶는다. 한 가입자가 보통 /64 전체를 받으므로 주소를 바꿔 가며 키를 늘리는 것을 막는다.
 */
@Component
public class ClientIpResolver {
    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final int MAX_ADDRESS_LENGTH = 64;
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    static final long CHAIN_LOG_INTERVAL_MILLIS = 10_000;
    /** 로그에 남길 헤더 원문 최대 길이. 클라이언트가 붙인 긴 값으로 로그가 커지지 않게 자른다. */
    private static final int MAX_LOGGED_CHAIN_LENGTH = 512;
    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final boolean trustForwardedHeader;
    private final int trustedProxyHops;
    private final boolean logForwardedChain;
    private final AtomicLong lastChainLogMillis = new AtomicLong(Long.MIN_VALUE);

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustForwardedHeader = properties.trustForwardedHeader();
        this.trustedProxyHops = properties.trustedProxyHops();
        this.logForwardedChain = properties.logForwardedChain();
    }

    public String resolve(HttpServletRequest request) {
        if (logForwardedChain) {
            logChain(request);
        }
        return resolve(request, trustForwardedHeader, trustedProxyHops);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader) {
        return resolve(request, trustForwardedHeader, 0);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader, int trustedProxyHops) {
        if (trustForwardedHeader) {
            String forwarded = normalize(forwardedAddress(request, trustedProxyHops));
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

    /** 오른쪽에서 hops 개를 건너뛴 값. 위치가 없으면 null. */
    private static String forwardedAddress(HttpServletRequest request, int hops) {
        List<String> chain = forwardedChain(request);
        int index = chain.size() - 1 - hops;
        return hops < 0 || index < 0 ? null : chain.get(index);
    }

    /**
     * 모든 X-Forwarded-For 줄을 순서대로 이어 붙인 값 목록(왼쪽이 가장 먼 쪽). 빈 줄은 건너뛰고, 줄 안의 빈 값("a,,b"·"a,")은
     * 자리를 지키도록 남긴다(선택되면 IP 가 아니라서 연결 주소로 돌아간다).
     */
    static List<String> forwardedChain(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        List<String> lines = headers == null ? List.of() : Collections.list(headers);
        List<String> chain = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            for (String part : line.split(",", -1)) {
                chain.add(part.trim());
            }
        }
        return chain;
    }

    private void logChain(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        long last = lastChainLogMillis.get();
        if (last != Long.MIN_VALUE && now - last < CHAIN_LOG_INTERVAL_MILLIS) {
            return;
        }
        if (!lastChainLogMillis.compareAndSet(last, now)) {
            return; // 다른 요청이 이번 구간의 로그를 남김
        }
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        String raw = headers == null ? "" : String.join(" | ", Collections.list(headers));
        if (raw.length() > MAX_LOGGED_CHAIN_LENGTH) {
            raw = raw.substring(0, MAX_LOGGED_CHAIN_LENGTH) + "...";
        }
        // 제어 문자를 지워 로그 줄 위조를 막는다.
        raw = raw.replaceAll("\\p{Cntrl}", "?");
        log.info("X-Forwarded-For 보정용: remoteAddr={}, X-Forwarded-For=[{}], trustedProxyHops={}",
                request.getRemoteAddr(), raw, trustedProxyHops);
    }

    private static String truncate(String value) {
        return value.length() > MAX_ADDRESS_LENGTH ? value.substring(0, MAX_ADDRESS_LENGTH) : value;
    }
}
