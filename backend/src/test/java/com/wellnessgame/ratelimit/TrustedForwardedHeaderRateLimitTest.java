package com.wellnessgame.ratelimit;

import com.wellnessgame.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.wellnessgame.support.TestRequests.fromIp;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #48 trust-forwarded-header=true: 같은 프록시(연결 주소)를 거쳐도 X-Forwarded-For 의 클라이언트별로 따로 센다.
 */
@SpringBootTest(properties = {
        "app.rate-limit.login-per-minute=1",
        "app.rate-limit.auth-per-minute=0",
        "app.rate-limit.trust-forwarded-header=true"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class TrustedForwardedHeaderRateLimitTest {
    private static final String PROXY = "10.0.0.1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void limitsPerForwardedClientBehindTrustedProxy() throws Exception {
        loginVia("203.0.113.1").andExpect(status().isBadRequest());
        loginVia("203.0.113.1").andExpect(status().isTooManyRequests());

        loginVia("203.0.113.2").andExpect(status().isBadRequest());
    }

    @Test
    void usesLastForwardedAddressAddedByProxyNotClientSuppliedFirstOne() throws Exception {
        loginVia("198.51.100.1, 203.0.113.10").andExpect(status().isBadRequest());

        loginVia("198.51.100.2, 203.0.113.10").andExpect(status().isTooManyRequests());
    }

    @Test
    void usesLastLineWhenProxyAddsSeparateHeaderLine() throws Exception {
        mockMvc.perform(fromIp(post("/api/auth/login"), PROXY)
                        .header("X-Forwarded-For", "198.51.100.3")
                        .header("X-Forwarded-For", "203.0.113.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"nobody_fwd\", \"password\": \"wrong-password\"}"))
                .andExpect(status().isBadRequest());

        loginVia("203.0.113.20").andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidForwardedValueFallsBackToProxyAddress() throws Exception {
        loginVia("203.0.113.30, not-an-ip").andExpect(status().isBadRequest());

        // 둘 다 프록시 주소(PROXY)로 세어진다.
        loginVia("203.0.113.31, also-bad").andExpect(status().isTooManyRequests());
    }

    private ResultActions loginVia(String forwardedFor) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/login"), PROXY)
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"nobody_fwd\", \"password\": \"wrong-password\"}"));
    }
}
