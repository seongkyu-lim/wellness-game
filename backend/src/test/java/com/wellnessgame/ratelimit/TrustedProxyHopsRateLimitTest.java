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

/** #102 trusted-proxy-hops=1: 프록시 두 단(CDN → Render) 뒤에서 오른쪽 하나를 건너뛴 값으로 센다. */
@SpringBootTest(properties = {
        "app.rate-limit.login-per-minute=1",
        "app.rate-limit.auth-per-minute=0",
        "app.rate-limit.trust-forwarded-header=true",
        "app.rate-limit.trusted-proxy-hops=1"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class TrustedProxyHopsRateLimitTest {
    private static final String RENDER = "10.0.0.1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void countsClientEntryBeforeTrustedProxyAndIgnoresSpoofedPrefix() throws Exception {
        loginVia("203.0.113.1, 198.51.100.200").andExpect(status().isBadRequest());
        // 왼쪽에 위조 값을 넣어도, 마지막 프록시 주소가 달라도 같은 클라이언트(203.0.113.1)로 센다.
        loginVia("9.9.9.9, 203.0.113.1, 198.51.100.201").andExpect(status().isTooManyRequests());

        loginVia("203.0.113.2, 198.51.100.200").andExpect(status().isBadRequest());
    }

    @Test
    void chainShorterThanHopsFallsBackToConnectionAddress() throws Exception {
        // 두 요청 모두 연결 주소(RENDER)로 세어진다.
        loginVia("203.0.113.9").andExpect(status().isBadRequest());
        loginVia("203.0.113.10").andExpect(status().isTooManyRequests());
    }

    private ResultActions loginVia(String forwardedFor) throws Exception {
        return mockMvc.perform(fromIp(post("/api/auth/login"), RENDER)
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"nobody_hops\", \"password\": \"wrong-password\"}"));
    }
}
