package com.wellnessgame.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    private static final String REMOTE = "10.0.0.1";

    @Test
    void ignoresForwardedHeaderUnlessTrusted() {
        MockHttpServletRequest request = request("1.2.3.4, 203.0.113.7");

        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo(REMOTE);
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.7");
    }

    @Test
    void usesLastValueOfLastHeaderLine() {
        MockHttpServletRequest request = request("198.51.100.1, 198.51.100.2");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 203.0.113.9");

        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.9");
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
}
