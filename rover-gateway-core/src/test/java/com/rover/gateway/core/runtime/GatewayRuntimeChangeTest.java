package com.rover.gateway.core.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.manage.ManageApiException;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteDiff;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteTarget;
import com.rover.gateway.core.runtime.GatewayRuntime.RouteChangeResult;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Author: Daylight
 * Created: 2026-09-27 15:30:00
 * Description: 版本化安全变更接口验收：乐观锁冲突、幂等重放、校验/落盘失败不改内存、重启读回与回滚
 *
 * 全程用 NAMESERVER 发现模式（{@code usesServiceDiscovery()==true}），因为 targets 形态的路由
 * 只在走服务发现时合法；ServiceDiscovery 传 null，本类不发真实请求，只验证变更协议本身。
 */
class GatewayRuntimeChangeTest {

    @TempDir
    Path tempDir;

    @Test
    void staleExpectedRevisionReturnsConflict409() {
        GatewayRuntime runtime = runtime(new RouteOverlayStore(tempDir.resolve("conflict.json")), List.of());
        assertEquals(0, runtime.getRevision(), "初始版本应为 0");

        RouteChangeResult first = runtime.applyRoutes(0, "op-1",
                List.of(versionedRoute("r1", "/api/a", 100)));
        assertEquals("APPLIED", first.status(), "首次提交应生效");
        assertEquals(1, first.revision(), "首次提交后版本应为 1");

        ManageApiException ex = assertThrows(ManageApiException.class,
                () -> runtime.applyRoutes(0, "op-2", List.of(versionedRoute("r2", "/api/b", 100))));
        assertEquals(HttpResponseStatus.CONFLICT, ex.getStatus(), "过期版本必须返回 409");
        assertEquals(409, ex.getStatus().code(), "冲突状态码应为 409");
        assertEquals(1, ((Number) ex.getDetails().get("currentRevision")).intValue(),
                "冲突响应必须带上当前版本号，调用方据此刷新重试");

        assertEquals(1, runtime.getRevision(), "冲突后内存版本号不得变化");
        assertEquals("/api/a", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "冲突后生效路由不得变化");
    }

    @Test
    void appliedOperationIdIsReplayedIdempotently() {
        GatewayRuntime runtime = runtime(new RouteOverlayStore(tempDir.resolve("replay.json")), List.of());
        runtime.applyRoutes(0, "op-1", List.of(versionedRoute("r1", "/api/a", 100)));

        // expectedRevision 已过期，但同一 operationId 应短路成幂等重放，不再走冲突判断
        RouteChangeResult replayed = runtime.applyRoutes(0, "op-1", List.of(versionedRoute("r2", "/api/b", 100)));

        assertEquals("REPLAYED", replayed.status(), "重复提交同一 operationId 必须返回 REPLAYED");
        assertEquals(1, replayed.revision(), "重放应返回原版本号");
        assertEquals(1, runtime.getRevision(), "重放不得产生新版本");
        assertEquals("/api/a", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "重放不得改动生效路由");
    }

    @Test
    void rejectedChangeKeepsMemoryAndOverlayUntouched() throws IOException {
        Path overlayPath = tempDir.resolve("reject.json");
        GatewayRuntime runtime = runtime(new RouteOverlayStore(overlayPath), List.of());
        runtime.applyRoutes(0, "op-ok", List.of(versionedRoute("r1", "/api/a", 100)));
        byte[] before = Files.readAllBytes(overlayPath);

        // 两个 target 不同 serviceName：走到校验阶段必须被拒
        RouteConfig invalid = new RouteConfig();
        invalid.setBusinessPrefix("/api/b");
        invalid.setTargets(new ArrayList<>(List.of(
                new RouteTarget("demo", "v1", 50), new RouteTarget("other", "v2", 50))));

        assertThrows(IllegalArgumentException.class, () -> runtime.applyRoutes(1, "op-bad", List.of(invalid)));

        assertEquals(1, runtime.getRevision(), "校验失败不得推进版本号");
        assertEquals("/api/a", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "校验失败不得改动生效路由");
        assertArrayEquals(before, Files.readAllBytes(overlayPath), "校验失败不得改写 overlay 文件");

        GatewayRuntime.OperationRecord record = runtime.operationOf("op-bad");
        assertNotNull(record, "被拒的操作也应留痕，供调用方确认");
        assertEquals(GatewayRuntime.OperationStatus.REJECTED, record.status(), "被拒操作的终态应为 REJECTED");
    }

    @Test
    void overlayWriteFailureKeepsOldRoutesInMemory() throws IOException {
        // 把 store 的父目录先造成一个普通文件，写 blocked/routes.json 时创建目录必然失败
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        RouteOverlayStore brokenStore = new RouteOverlayStore(blocked.resolve("routes.json"));

        GatewayRuntime runtime = runtime(brokenStore, List.of(versionedRoute("r1", "/api/a", 100)));
        assertEquals(0, runtime.getRevision(), "初始版本应为 0");

        assertThrows(IllegalStateException.class,
                () -> runtime.applyRoutes(0, "op-1", List.of(versionedRoute("r2", "/api/b", 100))),
                "落盘失败必须抛出异常而不是静默生效");

        assertEquals(0, runtime.getRevision(), "落盘失败后版本号必须保持旧值");
        assertEquals("/api/a", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "落盘失败后生效路由必须保持旧版本");
    }

    @Test
    void restartReloadsRevisionFromOverlay() {
        Path overlayPath = tempDir.resolve("restart.json");
        GatewayRuntime first = runtime(new RouteOverlayStore(overlayPath), List.of());
        first.applyRoutes(0, "op-x", List.of(versionedRoute("r1", "/api/a", 100)));

        RouteOverlayStore reopened = new RouteOverlayStore(overlayPath);
        RouteOverlayStore.AppliedState applied = reopened.loadOrNull();
        assertNotNull(applied, "刚写过的 overlay 必须能读回");
        assertEquals(1, applied.revision(), "重启后应读回落盘版本号");
        assertEquals("op-x", applied.appliedOperationId(), "重启后应读回产生该版本的操作 ID");

        GatewayRuntime restarted = runtime(new RouteOverlayStore(overlayPath), List.of());
        restarted.restoreRoutesRevision(applied.revision(), applied.appliedOperationId());
        assertEquals(1, restarted.getRevision(), "重启后报出的版本应与重启前一致");
        assertEquals("op-x", restarted.getAppliedOperationId(), "重启后应保留产生该版本的操作 ID");
    }

    @Test
    void rollbackHonorsExpectedRevisionAndDoesNotOverwriteNewerChanges() {
        GatewayRuntime runtime = runtime(new RouteOverlayStore(tempDir.resolve("rollback.json")), List.of());
        runtime.applyRoutes(0, "op-1", List.of(versionedRoute("r1", "/api/a", 100)));
        runtime.applyRoutes(1, "op-2", List.of(versionedRoute("r2", "/api/b", 100)));
        runtime.applyRoutes(2, "op-3", List.of(versionedRoute("r3", "/api/c", 100)));
        assertEquals(3, runtime.getRevision(), "连续三次 apply 后版本应为 3");

        RouteChangeResult rolled = runtime.rollback(3, "op-rb", 1);
        assertEquals("APPLIED", rolled.status(), "合法回滚应生效");
        assertEquals(4, runtime.getRevision(), "回滚本身是一次新的变更，版本应 +1");
        assertEquals("/api/a", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "回滚后路由内容应等于 1 号版本");

        // 已经回滚到 4 号版本，仍拿旧的 expectedRevision=3 回滚必须冲突，避免覆盖别人的新修改
        ManageApiException ex = assertThrows(ManageApiException.class,
                () -> runtime.rollback(3, "op-rb2", 1));
        assertEquals(409, ex.getStatus().code(), "过期的 expectedRevision 回滚必须返回 409");
        assertEquals(4, runtime.getRevision(), "冲突回滚不得推进版本号");
    }

    @Test
    void rollbackOutsideHistoryWindowIsRejectedWith400() {
        GatewayRuntime runtime = runtime(new RouteOverlayStore(tempDir.resolve("window.json")), List.of());
        for (int i = 1; i <= 7; i++) {
            RouteChangeResult result = runtime.applyRoutes(runtime.getRevision(), "op-" + i,
                    List.of(versionedRoute("r" + i, "/api/n" + i, 100)));
            assertEquals("APPLIED", result.status(), "第 " + i + " 次 apply 应生效");
        }
        assertEquals(7, runtime.getRevision(), "七次 apply 后版本应为 7");

        // 历史窗口只保留最近 5 个已应用快照，1 号版本已被挤出
        ManageApiException ex = assertThrows(ManageApiException.class,
                () -> runtime.rollback(7, "op-rb", 1));
        assertEquals(400, ex.getStatus().code(), "回滚窗口外的版本必须被明确拒绝（400）");
    }

    @Test
    void previewReturnsDiffWithoutApplyingOrPersisting() {
        Path overlayPath = tempDir.resolve("preview.json");
        GatewayRuntime runtime = runtime(new RouteOverlayStore(overlayPath),
                List.of(versionedRoute("r1", "/api/a", 100), versionedRoute("r3", "/api/c", 100)));
        assertEquals(0, runtime.getRevision(), "初始版本应为 0");

        List<RouteConfig> candidate = List.of(
                versionedRoute("r1", "/api/a", 50, 50),   // 与当前同 id 但 targets 不同 → MODIFIED
                versionedRoute("r2", "/api/b", 100, 0));  // 新路由 → ADDED；r3 不在候选 → REMOVED

        List<RouteDiff.Change> changes = runtime.previewRoutes(candidate);
        Set<String> kinds = changes.stream().map(RouteDiff.Change::kind).collect(Collectors.toSet());
        assertTrue(kinds.contains(RouteDiff.ADDED), "预览应报出新增路由，实际=" + kinds);
        assertTrue(kinds.contains(RouteDiff.REMOVED), "预览应报出删除路由，实际=" + kinds);
        assertTrue(kinds.contains(RouteDiff.MODIFIED), "预览应报出修改路由，实际=" + kinds);

        assertEquals(0, runtime.getRevision(), "预览不得推进版本号");
        assertFalse(Files.exists(overlayPath), "预览不得落盘生成 overlay 文件");
    }

    @Test
    void failedDiskWriteLeavesTraceAndCanBeRetriedAfterRecovery() throws IOException {
        // 把 store 的父目录先造成一个普通文件，写 blocked/routes.json 时创建目录必然失败
        Path blocked = tempDir.resolve("blocked-retry");
        Files.writeString(blocked, "not a directory");
        GatewayRuntime runtime = runtime(new RouteOverlayStore(blocked.resolve("routes.json")),
                List.of(versionedRoute("r1", "/api/a", 100)));

        assertThrows(IllegalStateException.class,
                () -> runtime.applyRoutes(0, "op-retry", List.of(versionedRoute("r2", "/api/b", 100))));

        // 留痕：丢了响应之后，调用方靠这条记录才能判断「提交过、但没写进去」
        GatewayRuntime.OperationRecord record = runtime.operationOf("op-retry");
        assertNotNull(record, "落盘失败也必须留痕，否则调用方只能看到 UNKNOWN");
        assertEquals(GatewayRuntime.OperationStatus.FAILED, record.status(), "落盘失败的终态应是 FAILED");
        assertEquals(0, record.revision(), "本操作没有产生新版本，记录里的版本应是当前版本");
        assertNotNull(record.message(), "应带上失败原因，便于判断是磁盘问题还是别的问题");

        // 关键：FAILED 不能被当成幂等重放，否则磁盘恢复后同一 operationId 再也重试不了
        Files.delete(blocked);
        Files.createDirectories(blocked);
        RouteChangeResult retried = runtime.applyRoutes(0, "op-retry", List.of(versionedRoute("r2", "/api/b", 100)));

        assertEquals("APPLIED", retried.status(), "磁盘恢复后同一 operationId 应能重新执行并生效");
        assertEquals(1, retried.revision(), "重试成功后版本应推进到 1");
        assertEquals("/api/b", runtime.getRouteMatcher().listRoutes().get(0).getBusinessPrefix(),
                "重试成功后路由应真的生效");
        assertEquals(GatewayRuntime.OperationStatus.APPLIED, runtime.operationOf("op-retry").status(),
                "重试成功后记录应翻成 APPLIED，不再停留在 FAILED");
    }

    /** 构造一个绑定指定 overlay 路径的 GatewayRuntime（NAMESERVER，无真实发现客户端）。 */
    private GatewayRuntime runtime(RouteOverlayStore overlayStore, List<RouteConfig> routes) {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.NAMESERVER);
        GatewayRuntimeConfigManager configManager = new GatewayRuntimeConfigManager(
                new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(tempDir.resolve("gw-overlay-" + System.nanoTime() + ".json")));
        return new GatewayRuntime(0, routes, 1000, 3000, new FilterSettings(), settings, null,
                configManager, "", overlayStore);
    }

    /** 一条动态路由：v1 接流、v2 权重为 0（总量为正，仅 v1 接流）。 */
    private static RouteConfig versionedRoute(String id, String prefix, int v1Weight) {
        return versionedRoute(id, prefix, v1Weight, 0);
    }

    /** 一条动态路由：显式给出 v1/v2 权重，用于制造可辨认的内容变化。 */
    private static RouteConfig versionedRoute(String id, String prefix, int v1Weight, int v2Weight) {
        RouteConfig route = new RouteConfig();
        route.setId(id);
        route.setBusinessPrefix(prefix);
        route.setTargets(new ArrayList<>(List.of(
                new RouteTarget("demo", "v1", v1Weight), new RouteTarget("demo", "v2", v2Weight))));
        return route;
    }
}
