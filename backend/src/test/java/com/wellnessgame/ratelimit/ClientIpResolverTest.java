package com.wellnessgame.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ClientIpResolverTest {
    private static final String REMOTE = "10.0.0.1";

    @Test
    void ignoresForwardedHeaderUnlessTrusted() {
        MockHttpServletRequest request = request("1.2.3.4, 203.0.113.7");

        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo(REMOTE);
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.7");
    }

    @Test
    void multipleHeaderLinesShareOneFixedKeyInsteadOfTrustingAnyLine() {
        MockHttpServletRequest request = request("198.51.100.1, 198.51.100.2");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 203.0.113.9");

        for (int hops = 0; hops < 3; hops++) {
            assertThat(ClientIpResolver.resolve(request, true, hops)).isEqualTo(ClientIpResolver.MULTIPLE_HEADER_KEY);
        }
        // 빈 줄이 하나 섞여도 여러 줄이다(공격자가 줄 구성을 바꿔 값을 고르지 못하게).
        MockHttpServletRequest withBlank = request(" ");
        withBlank.addHeader("X-Forwarded-For", "203.0.113.1");
        assertThat(ClientIpResolver.resolve(withBlank, true, 0)).isEqualTo(ClientIpResolver.MULTIPLE_HEADER_KEY);
        // 헤더를 믿지 않으면 연결 주소다.
        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo(REMOTE);
    }

    @Test
    void fallsBackToRemoteAddrWhenLastValueIsNotAnIpLiteral() {
        for (String forwarded : new String[]{
                "203.0.113.1, evil.example.com",
                "203.0.113.1, 999.1.1.1",
                "203.0.113.1, ",
                " , ",
                "1.2.3.4, " + "1".repeat(65),
                "2001:db8::1%eth0",
                "1.2.3",
                "not-an-ip"}) {
            assertThat(ClientIpResolver.resolve(request(forwarded), true)).as(forwarded).isEqualTo(REMOTE);
        }
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE);

        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo(REMOTE);
    }

    @Test
    void groupsIpv6ByPrefix64() {
        String a = ClientIpResolver.resolve(request("2001:db8:abcd:12:1111:2222:3333:4444"), true);
        String b = ClientIpResolver.resolve(request("[2001:0db8:abcd:0012::99]"), true);
        String other = ClientIpResolver.resolve(request("2001:db8:abcd:13::1"), true);

        assertThat(a).isEqualTo("2001:db8:abcd:12::/64").isEqualTo(b);
        assertThat(other).isEqualTo("2001:db8:abcd:13::/64");
    }

    @Test
    void normalizesIpv6RemoteAddrAndMappedIpv4() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("2001:db8:0:0:1:0:0:1");
        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo("2001:db8:0:0::/64");

        assertThat(ClientIpResolver.normalize("::ffff:192.0.2.5")).isEqualTo("192.0.2.5");
    }

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(REMOTE);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    // ---- #102 trusted-proxy-hops ----

    @Test
    void hopsSkipsThatManyRightmostTrustedProxyEntries() {
        // 클라이언트 → CDN(198.51.100.9 를 덧붙임) → Render(CDN 주소 203.0.113.50 을 덧붙임)
        MockHttpServletRequest request = request("203.0.113.7, 198.51.100.9, 203.0.113.50");

        assertThat(ClientIpResolver.resolve(request, true, 0)).isEqualTo("203.0.113.50");
        assertThat(ClientIpResolver.resolve(request, true, 1)).isEqualTo("198.51.100.9");
        assertThat(ClientIpResolver.resolve(request, true, 2)).isEqualTo("203.0.113.7");
    }

    @Test
    void spoofedLeftmostEntriesAreIgnored() {
        // 클라이언트가 임의의 값을 앞에 넣어도 프록시가 오른쪽에 덧붙인 값만 본다.
        for (String spoofed : new String[]{"1.1.1.1", "1.1.1.1, 2.2.2.2", "127.0.0.1, ::1, 10.0.0.9"}) {
            assertThat(ClientIpResolver.resolve(request(spoofed + ", 203.0.113.70"), true, 0))
                    .as(spoofed).isEqualTo("203.0.113.70");
        }
        // hops=1 이면 오른쪽 하나(신뢰 프록시)를 건너뛴 값이 클라이언트다. 그보다 왼쪽은 보지 않는다.
        assertThat(ClientIpResolver.resolve(request("6.6.6.6, 203.0.113.71, 10.1.1.1"), true, 1))
                .isEqualTo("203.0.113.71");
    }

    @Test
    void hopsBeyondChainOrInvalidSelectedEntryFallsBackToRemoteAddr() {
        assertThat(ClientIpResolver.resolve(request("203.0.113.7"), true, 1)).isEqualTo(REMOTE);
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, 198.51.100.9"), true, 5)).isEqualTo(REMOTE);
        // 선택된 위치가 IP 가 아니면 왼쪽 값으로 넘어가지 않고 연결 주소로 돌아간다(위조 값 선택 방지).
        assertThat(ClientIpResolver.resolve(request("203.0.113.7, not-an-ip, 198.51.100.9"), true, 1)).isEqualTo(REMOTE);
        // 빈 값도 자리를 차지한다: "a," 의 마지막 값은 빈 문자열이다.
        assertThat(ClientIpResolver.resolve(request("203.0.113.7,"), true, 0)).isEqualTo(REMOTE);
        assertThat(ClientIpResolver.resolve(request("203.0.113.7,,198.51.100.9"), true, 1)).isEqualTo(REMOTE);
    }

    @Test
    void instanceUsesConfiguredHops() {
        ClientIpResolver resolver = new ClientIpResolver(properties(true, 1, false));
        assertThat(resolver.resolve(request("203.0.113.7, 198.51.100.9"))).isEqualTo("203.0.113.7");
        assertThat(new ClientIpResolver(properties(false, 1, false)).resolve(request("203.0.113.7, 198.51.100.9")))
                .isEqualTo(REMOTE);
    }

    @Test
    void negativeHopsAreRejectedAtStartup() {
        assertThatIllegalStateException().isThrownBy(() -> properties(true, -1, false));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsForwardedChainAtMostOncePerInterval(CapturedOutput output) {
        ClientIpResolver resolver = new ClientIpResolver(properties(true, 0, true));
        for (int i = 0; i < 5; i++) {
            resolver.resolve(request("198.51.100.1, 203.0.113.80\r\nINJECTED\u2028X\u0085Y\u2029Z"));
        }

        String logged = output.getOut() + output.getErr();
        assertThat(logged.split("X-Forwarded-For 보정용", -1)).hasSize(2);
        assertThat(logged).contains("remoteAddr=" + REMOTE).contains("198.51.100.1, 203.0.113.80??INJECTED?X?Y?Z");
    }

    private static RateLimitProperties properties(boolean trust, int hops, boolean logChain) {
        return new RateLimitProperties(30, 30, 10, 120, trust, hops, logChain, 1000);
    }
}
