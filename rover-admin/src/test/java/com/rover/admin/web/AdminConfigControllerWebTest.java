package com.rover.admin.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.service.AdminConfigService;
import java.util.List;
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

    @Test
    void previewsRouteDiff() throws Exception {
        // 网关响应体含 changes/changeCount/revision，Admin 只做透传，因此原样断言这几项
        when(configService.previewRoutes(List.of(Map.of("businessPrefix", "/orders"))))
                .thenReturn(Map.of(
                        "revision", 3,
                        "message", "预览通过，未落盘、未生效",
                        "changes", List.of(Map.of(
                                "kind", "ADDED", "routeId", "", "businessPrefix", "/orders", "detail", "")),
                        "changeCount", 1));

        mockMvc.perform(post("/api/routes/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routes\":[{\"businessPrefix\":\"/orders\"}]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"revision\":3,\"changeCount\":1,"
                        + "\"changes\":[{\"kind\":\"ADDED\",\"businessPrefix\":\"/orders\"}]}"));
    }

    /**
     * 下游 409 必须原样透出。
     *
     * 压成 400 会让前端把「版本过期」理解成「参数写错了」，于是反复重提同一个过期版本，
     * 操作者永远看不到「期望几、当前几」这条唯一能自救的信息。
     */
    @Test
    void keepsDownstreamConflictStatusAndMessage() throws Exception {
        when(configService.saveRoute(Map.of("businessPrefix", "/orders")))
                .thenThrow(new ManageApiCallException(409, "版本冲突：期望 1，当前 2"));

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessPrefix\":\"/orders\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"code\":409,\"message\":\"版本冲突：期望 1，当前 2\"}"));
    }

    /** 没拿到下游响应（超时/连接失败）是 502，且不回声下游内部细节。 */
    @Test
    void mapsMissingDownstreamResponseToBadGateway() throws Exception {
        when(configService.saveRoute(Map.of("businessPrefix", "/orders")))
                .thenThrow(new ManageApiCallException(0, "调用管理接口失败（连接失败或超时）: /_manage/routes"));

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessPrefix\":\"/orders\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{\"code\":502,\"message\":\"下游组件不可用或请求处理失败\"}"));
    }

    /** 写请求超时后用同一个 operationId 回查终态。 */
    @Test
    void queriesRouteOperationById() throws Exception {
        when(configService.routeOperation("op-1"))
                .thenReturn(Map.of("operationId", "op-1", "status", "APPLIED", "revision", 3));

        mockMvc.perform(get("/api/routes/operations/op-1"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"operationId\":\"op-1\",\"status\":\"APPLIED\",\"revision\":3}"));
    }

    /**
     * 灰度放量 / 停推：只改一个版本权重的专用通道。
     *
     * 与「保存整条路由」走不同的管理口原语（更窄，不会误动同一条路由上的其它目标），
     * 但 revision 乐观锁与 operationId 超时回查的语义一致，因此错误透传也完全一致。
     */
    @Test
    void adjustsSingleTargetWeight() throws Exception {
        when(configService.adjustTargetWeight(Map.of(
                "routeId", "/orders",
                "serviceName", "order-service",
                "group", "v2",
                "weight", 20,
                "revision", 3,
                "operationId", "op-1")))
                .thenReturn(Map.of("ok", true, "message", "已调整版本权重"));

        mockMvc.perform(post("/api/routes/targets/weight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"/orders\",\"serviceName\":\"order-service\","
                                + "\"group\":\"v2\",\"weight\":20,\"revision\":3,\"operationId\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }

    /** 回滚到指定版本：网关以「产生新版本」的方式回滚，终态同样可回查。 */
    @Test
    void rollsBackRoutesToRevision() throws Exception {
        when(configService.rollbackRoutes(Map.of("toRevision", 2, "revision", 3, "operationId", "op-2")))
                .thenReturn(Map.of("ok", true, "message", "已回滚到版本 2"));

        mockMvc.perform(post("/api/routes/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toRevision\":2,\"revision\":3,\"operationId\":\"op-2\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
    }

    /** 回滚目标不在快照窗口内：网关的拒绝原因原样透出，操作者才知道该换哪个版本号。 */
    @Test
    void keepsRollbackRejectionFromDownstream() throws Exception {
        when(configService.rollbackRoutes(Map.of("toRevision", 99, "revision", 3, "operationId", "op-3")))
                .thenThrow(new ManageApiCallException(400, "回滚目标版本不在最近 5 次已应用快照内: 99"));

        mockMvc.perform(post("/api/routes/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toRevision\":99,\"revision\":3,\"operationId\":\"op-3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"code\":400,\"message\":\"回滚目标版本不在最近 5 次已应用快照内: 99\"}"));
    }
}
