package com.rover.admin.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 反向代理之后的登录限流：只有显式打开 {@code trust-forwarded-headers} 才按
 * {@code X-Forwarded-For} 的首个地址计数。
 *
 * 与 {@link AdminSecurityWebTest}（默认不信任）配成一对：开关两侧的行为都要有据可依。
 */
@SpringBootTest(properties = {
        "rover.admin.auth.username=ops",
        "rover.admin.auth.password=correct-horse",
        "rover.admin.auth.max-login-failures=3",
        "rover.admin.auth.trust-forwarded-headers=true"
})
@AutoConfigureMockMvc
class AdminSecurityTrustedProxyTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void countsFailuresPerForwardedClientAddress() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/login").with(proxy()).with(csrf())
                            .header("X-Forwarded-For", "203.0.113.7")
                            .param("username", "ops")
                            .param("password", "nope"))
                    .andExpect(status().isFound());
        }

        // 同一客户端（同一个 forwarded 地址）达到上限后直接被拒。
        mockMvc.perform(post("/login").with(proxy()).with(csrf())
                        .header("X-Forwarded-For", "203.0.113.7")
                        .param("username", "ops")
                        .param("password", "correct-horse"))
                .andExpect(status().isTooManyRequests());

        // 直连地址相同但 forwarded 地址不同：说明限流依据确实来自 X-Forwarded-For。
        mockMvc.perform(post("/login").with(proxy()).with(csrf())
                        .header("X-Forwarded-For", "203.0.113.8")
                        .param("username", "ops")
                        .param("password", "correct-horse"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    /** 代理自己的直连地址；带逗号的 forwarded 链只取第一个（最靠近客户端的那个）。 */
    private static RequestPostProcessor proxy() {
        return request -> {
            request.setRemoteAddr("10.20.0.1");
            return request;
        };
    }
}