package com.rover.admin.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 访问控制端到端：未登录 401 / 登录页放行 / 口令校验 / CSRF / 限流。
 *
 * 用真实过滤链（不加 {@code addFilters = false}），否则测不到"到底拦没拦住"。
 */
@SpringBootTest(properties = {
        "rover.admin.auth.username=ops",
        "rover.admin.auth.password=correct-horse",
        "rover.admin.auth.max-login-failures=3",
        "rover.admin.auth.failure-window-seconds=600"
})
@AutoConfigureMockMvc
class AdminSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    /** 每个用例换一个来源地址：LoginAttemptGuard 是共享单例，同址会让用例互相污染。 */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    void rejectsApiCallsWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/overview").with(from("10.10.0.1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录控制台"));
    }

    @Test
    void redirectsPageRequestsToLoginPage() throws Exception {
        mockMvc.perform(get("/").with(from("10.10.0.2")))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void servesLoginPageAndAuthStatusWithoutLogin() throws Exception {
        mockMvc.perform(get("/login.html").with(from("10.10.0.3")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loginForm")));

        mockMvc.perform(get("/api/auth/status").with(from("10.10.0.3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(false))
                // 令牌必须下发，否则登录页无法提交表单、SPA 也无法发写请求。
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void acceptsCorrectPasswordAndConfirmsLogin() throws Exception {
        mockMvc.perform(post("/login").with(from("10.10.0.4")).with(csrf())
                        .param("username", "ops")
                        .param("password", "correct-horse"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void rejectsWrongPasswordAndBouncesBackToLoginPage() throws Exception {
        mockMvc.perform(post("/login").with(from("10.10.0.5")).with(csrf())
                        .param("username", "ops")
                        .param("password", "nope"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html?error=1"));
    }

    @Test
    void blocksSourceAfterTooManyFailures() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/login").with(from("10.10.0.6")).with(csrf())
                            .param("username", "ops")
                            .param("password", "nope"))
                    .andExpect(status().isFound());
        }

        // 达到上限后连口令都不再校验，直接 429，避免 BCrypt 被反复消耗。
        mockMvc.perform(post("/login").with(from("10.10.0.6")).with(csrf())
                        .param("username", "ops")
                        .param("password", "correct-horse"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    /** 伪造的 X-Forwarded-For 不改变限流来源：默认只认直连地址，否则该头可以被用来绕过限流。 */
    @Test
    void ignoresForgedForwardedHeaderByDefault() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/login").with(from("10.10.0.9")).with(csrf())
                            .header("X-Forwarded-For", "203.0.113." + attempt)
                            .param("username", "ops")
                            .param("password", "nope"))
                    .andExpect(status().isFound());
        }

        mockMvc.perform(post("/login").with(from("10.10.0.9")).with(csrf())
                        .header("X-Forwarded-For", "198.51.100.7")
                        .param("username", "ops")
                        .param("password", "correct-horse"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rejectsWriteRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/configs").with(from("10.10.0.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"component\":\"gateway\",\"key\":\"a\",\"value\":\"b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRequiresPost() throws Exception {
        // GET 登出必须无效，否则恶意页面能借浏览器把操作者踢下线。
        mockMvc.perform(get("/api/logout").with(from("10.10.0.8")))
                .andExpect(status().isUnauthorized());
    }
}