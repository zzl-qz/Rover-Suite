package com.rover.admin.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.InvestigationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DiagnosisController.class)
class DiagnosisControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvestigationService investigations;

    @Test
    void createsAndReadsDiagnosisTask() throws Exception {
        TaskView task = new TaskView("task-1", "session-1", "incident-1", TaskStatus.PENDING,
                "/api/demo/tt", "为什么失败？", 1, 0, List.of(), null, null);
        when(investigations.submit(eq("/api/demo/tt"), eq("为什么失败？"))).thenReturn(task);
        when(investigations.get("task-1")).thenReturn(task);

        mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/demo/tt\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.incidentId").value("incident-1"));
        mockMvc.perform(get("/api/agent/diagnoses/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/agent/diagnoses/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsBusyWhenTaskQueueIsFull() throws Exception {
        when(investigations.submit(any(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("满"));

        mockMvc.perform(post("/api/agent/diagnoses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/api/demo/tt\",\"question\":\"为什么失败？\"}"))
                .andExpect(status().isTooManyRequests());
    }
}