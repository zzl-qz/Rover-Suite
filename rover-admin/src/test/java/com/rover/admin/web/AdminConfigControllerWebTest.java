package com.rover.admin.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rover.admin.service.AdminConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminConfigController.class)
class AdminConfigControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminConfigService configService;

    @Test
    void servesAdminPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Rover Admin</title>")))
                .andExpect(content().string(containsString("/js/pages/workbench.js")));
    }

    @Test
    void serializesJackson2Metrics() throws Exception {
        when(configService.loadNameserverMetrics())
                .thenReturn(JsonNodeFactory.instance.objectNode().put("registrations", 1));

        mockMvc.perform(get("/api/nameserver/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"registrations\":1}"));
    }

    @Test
    void acceptsRouteJson() throws Exception {
        when(configService.saveRoute(Map.of("businessPrefix", "/orders")))
                .thenReturn(Map.of("ok", true));

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessPrefix\":\"/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }
}
