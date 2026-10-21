package com.rover.admin.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.security.AdminUserStore;
import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.action.ActionRequestException;
import com.rover.agent.runtime.action.AgentActionService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 使用真实认证、实时角色与 CSRF 链，检查计划版本 API 的受理边界。 */
@SpringBootTest(properties = "rover.admin.log-store-path=./target/test-data/AgentPlanControllerWebTest/rover")
@AutoConfigureMockMvc
class AgentPlanControllerWebTest {
    private static final String URL = "/api/agent/v1/plans/plan-1/versions";
    private static final String HASH = "a".repeat(64);
    private static final String BODY = "{\"expectedVersion\":1,\"expectedPlanHash\":\"" + HASH
            + "\",\"requestedValue\":20,\"requestedUnit\":\"RAW_WEIGHT\"}";

    @Autowired MockMvc http;
    @Autowired AdminUserStore users;
    @Autowired RouteControlPort control;
    @MockitoBean AgentActionService actions;
    // 此测试验证 Web 边界；隔离管理端外部 HTTP 客户端，避免测试启动时创建真实网络资源。
    @MockitoBean ManageHttpClient manageHttpClient;

    @BeforeEach void roles() {
        users.saveAccount("plan-operator", "test-operator-password", Set.of("OPERATOR"), control.resourceNamespace(), true);
        users.saveAccount("plan-reader", "test-reader-password", Set.of("VIEWER"), control.resourceNamespace(), true);
    }

    @Test void anonymousCannotReadOrRevise() throws Exception {
        http.perform(get(URL)).andExpect(status().isUnauthorized());
        http.perform(get(URL + "/1")).andExpect(status().isUnauthorized());
        http.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(actions, never()).planVersions(anyString(), any());
        verify(actions, never()).revisePlan(anyString(), anyInt(), anyString(), any(), any(), any());
    }

    @Test void readsUseAuthenticatedIdentityAndHideMissingOrUnownedPlans() throws Exception {
        when(actions.planVersions("plan-1", "plan-reader")).thenReturn(Optional.of(List.of()));
        when(actions.plan("plan-1", 1, "plan-reader")).thenReturn(Optional.empty());
        http.perform(get(URL).with(user("plan-reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        http.perform(get(URL + "/1").with(user("plan-reader"))).andExpect(status().isNotFound());
        http.perform(get("/api/agent/v1/plans/foreign/versions").with(user("plan-reader")))
                .andExpect(status().isNotFound());
        verify(actions).planVersions("plan-1", "plan-reader");
        verify(actions).plan("plan-1", 1, "plan-reader");
    }

    @Test void viewerCannotReviseAndStaleSessionCannotBypassRevocationOrScope() throws Exception {
        http.perform(post(URL).with(user("plan-reader").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        users.saveAccount("plan-operator", null, Set.of("OPERATOR"), control.resourceNamespace(), false);
        http.perform(post(URL).with(user("plan-operator").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        users.saveAccount("plan-reader", null, Set.of("VIEWER"), "another-environment", true);
        http.perform(get(URL).with(user("plan-reader").roles("ADMIN"))).andExpect(status().isForbidden());
        verify(actions, never()).revisePlan(anyString(), anyInt(), anyString(), any(), any(), any());
    }

    @Test void revisionsRequireBrowserCsrfAndDoNotAcceptConnectorCredentials() throws Exception {
        http.perform(post(URL).with(user("plan-operator")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        http.perform(post(URL).header("Authorization", "Bearer connector-only").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verify(actions, never()).revisePlan(anyString(), anyInt(), anyString(), any(), any(), any());
    }

    @Test void revisionPassesExpectedVersionHashUnitAndActorToDomainService() throws Exception {
        AgentAction revised = new AgentAction("action-v2", "s1", null, null,
                ActionType.ADJUST_ROUTE_TARGET_WEIGHT, ActionStatus.PENDING_APPROVAL,
                "orders", "/orders", "order-service", "v2", 10, 20, 20, WeightRequestUnit.RAW_WEIGHT,
                10, 18.2, 4, null, null, null, "plan-operator", null, 0,
                List.of(), "缩量", null, 1, 1);
        when(actions.revisePlan("plan-1", 1, HASH, 20, WeightRequestUnit.RAW_WEIGHT, "plan-operator"))
                .thenReturn(revised);
        http.perform(post(URL).with(user("plan-operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actionId").value("action-v2"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        verify(actions).revisePlan("plan-1", 1, HASH, 20, WeightRequestUnit.RAW_WEIGHT, "plan-operator");
    }

    @Test void refusesUnknownMissingAmbiguousAndCoercedFieldsBeforeCreatingAnyVersion() throws Exception {
        for (String invalid : List.of("{}", "null", "[]", "{", BODY.replace("1,", "0,"),
                BODY.replace("1,", "1.5,"), BODY.replace("1,", "\"1\","),
                BODY.replace("20,", "20.0,"), BODY.replace("20,", "2147483648,"),
                BODY.replace("20,", "-1,"), BODY.replace("20,", "10001,"),
                BODY.replace("20,", "null,"), BODY.replace("RAW_WEIGHT", "WEIGHT"),
                BODY.replace("RAW_WEIGHT", "UNSURE"), BODY.replace("RAW_WEIGHT", "TRAFFIC_PERCENT").replace("20,", "101,"),
                BODY.replace(HASH, "wrong"), BODY.replace(",\"requestedUnit\":\"RAW_WEIGHT\"", ""),
                BODY.replace("}", ",\"userId\":\"admin\"}"), BODY.replace("}", ",\"steps\":[]}"))) {
            http.perform(post(URL).with(user("plan-operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(actions, never()).revisePlan(anyString(), anyInt(), anyString(), any(), any(), any());
    }

    @Test void invalidVersionPathsAreBadRequests() throws Exception {
        for (String version : List.of("0", "-1", "not-a-number")) {
            http.perform(get(URL + "/" + version).with(user("plan-reader")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(actions, never()).plan(anyString(), anyInt(), any());
    }

    @Test void mapsDomainConflictsAndMissingPlansWithoutReclassifyingThemAsServerFailures() throws Exception {
        for (var code : ActionRequestException.Code.values()) {
            doThrow(new ActionRequestException(code, "计划请求被拒绝"))
                    .when(actions).revisePlan("plan-1", 1, HASH, 20, WeightRequestUnit.RAW_WEIGHT, "plan-operator");
            int expected = switch (code) { case INVALID_REQUEST -> 400; case NOT_FOUND -> 404; case CONFLICT -> 409; };
            http.perform(post(URL).with(user("plan-operator")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code.name()));
        }
    }
}
