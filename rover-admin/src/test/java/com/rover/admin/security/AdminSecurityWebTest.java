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

/** 未登录进不了控制台；默认账号口令都是 admin。 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsApiCallsWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("请先登录控制台"));
    }

    @Test
    void redirectsPageRequestsToLoginPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void servesLoginPageAndAuthStatusWithoutLogin() throws Exception {
        mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loginForm")));

        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void acceptsDefaultAccount() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", AdminUserStore.DEFAULT_USERNAME)
                        .param("password", AdminUserStore.DEFAULT_PASSWORD))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", AdminUserStore.DEFAULT_USERNAME)
                        .param("password", "nope"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html?error=1"));
    }

    @Test
    void rejectsWriteRequestWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"component\":\"gateway\",\"key\":\"a\",\"value\":\"b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRequiresPost() throws Exception {
        mockMvc.perform(get("/api/logout"))
                .andExpect(status().isUnauthorized());
    }
}
