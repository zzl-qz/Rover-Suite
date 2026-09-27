package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Author: Daylight
 * Created: 2026-09-27 17:40:00
 * Description: 路由覆盖文件读写契约：不可用时返回 null（回退 YAML）而不是抛异常或以零路由启动
 *
 * <p>回归背景：旧版本把 overlay 写成**裸数组**，新格式是带版本元信息的包装对象。
 * 旧文件留在磁盘上时，解析失败曾把整个 Gateway 启动打断在 main 里。这里刻意不抛异常，
 * 但也**不能**返回「空版本」——把「读不出来」当成「路由表为空」会让网关零路由启动。
 */
class RouteOverlayStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileReturnsNull() {
        RouteOverlayStore store = new RouteOverlayStore(tempDir.resolve("absent.json"));
        assertNull(store.loadOrNull(), "文件不存在时应返回 null，由调用方回退 YAML");
    }

    @Test
    void blankFileReturnsNull() throws IOException {
        Path path = tempDir.resolve("blank.json");
        Files.writeString(path, "   \n", StandardCharsets.UTF_8);
        RouteOverlayStore store = new RouteOverlayStore(path);
        assertNull(store.loadOrNull(), "空文件应返回 null，而不是当成「路由表为空」");
    }

    @Test
    void legacyArrayFileReturnsNullInsteadOfThrowing() throws IOException {
        Path path = tempDir.resolve("routes.overlay.json");
        // 旧格式：裸数组 + 扁平的 serviceName/group 字段（targets 模型之前的样子）
        Files.writeString(path, """
                [ {
                  "id" : "demo-api",
                  "businessPrefix" : "/api",
                  "targetUrl" : "",
                  "targetUrls" : "",
                  "serviceName" : "demo-service",
                  "group" : "",
                  "stripPrefix" : ""
                } ]
                """, StandardCharsets.UTF_8);

        RouteOverlayStore store = new RouteOverlayStore(path);
        assertNull(store.loadOrNull(), "旧格式文件应被忽略并回退 YAML，而不是把启动打断");
    }

    @Test
    void emptyRoutesObjectIsStillAUsableState() throws IOException {
        Path path = tempDir.resolve("emptied.json");
        Files.writeString(path, "{\"revision\":3,\"appliedOperationId\":\"op-3\",\"appliedAtMillis\":9,\"routes\":[]}",
                StandardCharsets.UTF_8);

        RouteOverlayStore.AppliedState applied = new RouteOverlayStore(path).loadOrNull();
        assertNotNull(applied, "格式合法的空路由表是「运维真的清空了路由」，不等于「没有覆盖文件」");
        assertEquals(3, applied.revision(), "空路由表也要保留版本号");
        assertEquals("op-3", applied.appliedOperationId(), "空路由表也要保留操作 ID");
        assertTrue(applied.routes().isEmpty(), "空路由表应解析成空列表");
    }

    @Test
    void savedStateRoundTripsWithTargets() {
        Path path = tempDir.resolve("round-trip.json");
        RouteOverlayStore store = new RouteOverlayStore(path);

        RouteConfig route = new RouteConfig();
        route.setId("demo-api");
        route.setBusinessPrefix("/api");
        route.setStickyHeader("X-User-Id");
        route.setTargets(new ArrayList<>(List.of(
                new RouteTarget("demo-service", "v1", 95),
                new RouteTarget("demo-service", "v2", 5))));
        store.save(2, "op-2", 1234L, List.of(route));

        RouteOverlayStore.AppliedState applied = new RouteOverlayStore(path).loadOrNull();
        assertNotNull(applied, "刚写过的 overlay 必须能读回");
        assertEquals(2, applied.revision());
        assertEquals("op-2", applied.appliedOperationId());
        assertEquals(1234L, applied.appliedAtMillis(), "落盘时刻应原样读回");

        RouteConfig restored = applied.routes().get(0);
        assertEquals("demo-api", restored.getId());
        assertEquals("X-User-Id", restored.getStickyHeader());
        assertEquals(2, restored.getTargets().size(), "灰度权重必须完整读回");
        assertEquals("v2", restored.getTargets().get(1).group());
        assertEquals(5, restored.getTargets().get(1).weight());
    }
}
