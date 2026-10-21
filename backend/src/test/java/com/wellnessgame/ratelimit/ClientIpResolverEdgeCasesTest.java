package com.wellnessgame.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 키 정규화의 가장자리: 연결 주소가 비정상이거나 헤더가 특이한 경우. */
class ClientIpResolverEdgeCasesTest {

    private static MockHttpServletRequest requestFrom(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        return request;
    }

    @Test
    void blankOrNullRemoteAddrBecomesUnknown() {
        assertThat(ClientIpResolver.resolve(requestFrom(""), false)).isEqualTo("unknown");
        assertThat(ClientIpResolver.resolve(requestFrom("   "), false)).isEqualTo("unknown");
        assertThat(ClientIpResolver.resolve(requestFrom(null), true)).isEqualTo("unknown");
    }

    @Test
    void nonIpRemoteAddrIsKeptButTruncatedToMaxLength() {
        assertThat(ClientIpResolver.resolve(requestFrom("proxy.internal"), false)).isEqualTo("proxy.internal");
        String longValue = "h".repeat(200);
        assertThat(ClientIpResolver.resolve(requestFrom(longValue), false))
                .hasSize(ClientIpResolver.MAX_ADDRESS_LENGTH).isEqualTo("h".repeat(64));
    }

    @Test
    void ipv4OctetBoundary() {
        assertThat(ClientIpResolver.normalize("255.255.255.255")).isEqualTo("255.255.255.255");
        assertThat(ClientIpResolver.normalize("0.0.0.0")).isEqualTo("0.0.0.0");
        assertThat(ClientIpResolver.normalize("256.1.1.1")).isNull();
        assertThat(ClientIpResolver.normalize("1.1.1.300")).isNull();
        assertThat(ClientIpResolver.normalize("1.1.1.1.1")).isNull();
    }

    @Test
    void bracketedAndWhitespacePaddedValuesAreNormalized() {
        assertThat(ClientIpResolver.normalize("  203.0.113.5  ")).isEqualTo("203.0.113.5");
        assertThat(ClientIpResolver.normalize("[2001:db8:1:2::9]")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientIpResolver.normalize("[]")).isNull();
        assertThat(ClientIpResolver.normalize(null)).isNull();
        assertThat(ClientIpResolver.normalize("   ")).isNull();
    }

    @Test
    void sameIpv6PrefixSharesOneKeyButDifferentPrefixDoesNot() {
        String a = ClientIpResolver.normalize("2001:db8:aaaa:bbbb:1:2:3:4");
        String b = ClientIpResolver.normalize("2001:db8:aaaa:bbbb:ffff:ffff:ffff:ffff");
        String c = ClientIpResolver.normalize("2001:db8:aaaa:bbbc:1:2:3:4");

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void invalidIpv6LiteralsAreRejectedWithoutDnsLookup() {
        assertThat(ClientIpResolver.normalize("::::::")).isNull();
        assertThat(ClientIpResolver.normalize("gggg::1")).isNull();
        assertThat(ClientIpResolver.normalize("1:2:3:4:5:6:7:8:9")).isNull();
    }

    @Test
    void forwardedChainSkipsBlankLinesButKeepsEmptySlots() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "  ");
        request.addHeader("X-Forwarded-For", "1.1.1.1, ,2.2.2.2");

        assertThat(ClientIpResolver.forwardedChain(request)).containsExactly("1.1.1.1", "", "2.2.2.2");
    }

    @Test
    void trustedHeaderWithOnlyBlankLineFallsBackToRemote() {
        MockHttpServletRequest request = requestFrom("192.0.2.9");
        request.addHeader("X-Forwarded-For", "");

        assertThat(ClientIpResolver.resolve(request, true, 0)).isEqualTo("192.0.2.9");
    }

    @Test
    void negativeHopsFallBackToRemote() {
        MockHttpServletRequest request = requestFrom("192.0.2.9");
        request.addHeader("X-Forwarded-For", "203.0.113.1");

        assertThat(ClientIpResolver.resolve(request, true, -1)).isEqualTo("192.0.2.9");
    }

    @Test
    void longForwardedLogLineIsTruncated() {
        ClientIpResolver resolver = new ClientIpResolver(new RateLimitProperties(30, 30, 10, 120, true, 0, true, 100));
        MockHttpServletRequest request = requestFrom("192.0.2.9");
        request.addHeader("X-Forwarded-For", "9".repeat(2_000));

        // 예외 없이 처리되고, 값이 IP 가 아니므로 연결 주소가 키다.
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.9");
    }
}
