package com.rover.gateway.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.manage.ManageApiException;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteOperationStore;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDurableReceiptTest {
    @TempDir Path directory;

    @Test
    void originalResultSurvivesManualChangeRestartAndMoreThan200Operations() {
        Path path = directory.resolve("routes.json");
        GatewayRuntime first = runtime(new RouteOverlayStore(path));
        first.applyRoutes(0, "first", List.of(route(10)));
        first.applyRoutes(1, "manual", List.of(route(20)));
        for (int i = 0; i < 205; i++) {
            first.adjustTargetWeight(first.getRevision(), "later-" + i, "orders", "demo", "v2", 30 + i % 2);
        }
        GatewayRuntime restarted = restart(path);
        assertEquals(207, restarted.getRevision());
        assertEquals(GatewayRuntime.OperationStatus.APPLIED, restarted.operationOf("first").status());
        assertEquals(1, restarted.operationOf("first").revision());
        var replay = restarted.applyRoutes(0, "first", List.of(route(10)));
        assertEquals("REPLAYED", replay.status());
        assertEquals(10, replay.routes().get(0).getTargets().get(1).weight());
        assertEquals(30, restarted.getRouteMatcher().listRoutes().get(0).getTargets().get(1).weight());
        assertEquals(207, restarted.getRevision());
        first.close();
        restarted.close();
    }

    @Test
    void sameIdCannotChangeParametersRevisionOrActionAndCannotOverwriteReceipt() {
        GatewayRuntime runtime = runtime(new RouteOverlayStore(directory.resolve("matching.json")));
        runtime.applyRoutes(0, "one", List.of(route(10)));
        String hash = runtime.operationOf("one").requestHash();
        assertEquals(64, hash.length());
        assertMismatch(() -> runtime.applyRoutes(0, "one", List.of(route(20))));
        assertMismatch(() -> runtime.applyRoutes(1, "one", List.of(route(10))));
        assertMismatch(() -> runtime.addOrReplaceRoute(0, "one", route(10)));
        assertMismatch(() -> runtime.removeRoute(0, "one", "orders"));
        assertMismatch(() -> runtime.rollback(0, "one", 0));
        assertEquals(hash, runtime.operationOf("one").requestHash());
        assertEquals(1, runtime.getRevision());
        runtime.close();
    }

    @Test
    void expiredReceiptLeavesPermanentTombstoneAndRejectsExecutionAfterRestart() {
        Path path = directory.resolve("expiry.json");
        Clock initial = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
        GatewayRuntime first = runtime(new RouteOverlayStore(path, initial));
        first.applyRoutes(0, "old", List.of(route(10)));
        RouteOverlayStore store = new RouteOverlayStore(path, Clock.fixed(Instant.ofEpochMilli(
                1000 + RouteOperationStore.RECEIPT_RETENTION_MILLIS), ZoneOffset.UTC));
        GatewayRuntime restarted = runtime(store);
        var state = store.loadOrNull();
        restarted.restoreRoutesRevision(state.revision(), state.appliedOperationId());
        assertEquals(GatewayRuntime.OperationStatus.EXPIRED, restarted.operationOf("old").status());
        ManageApiException failure = assertThrows(ManageApiException.class,
                () -> restarted.applyRoutes(0, "old", List.of(route(10))));
        assertEquals("OPERATION_EXPIRED", failure.getDetails().get("code"));
        assertEquals(1, restarted.getRevision());
        first.close();
        restarted.close();
    }

    @Test
    void failedStorageTransactionCannotChangeRoutesOrClaimApplied() throws Exception {
        Path path = directory.resolve("disk.json");
        RouteOverlayStore store = new RouteOverlayStore(path);
        GatewayRuntime runtime = runtime(store);
        runtime.applyRoutes(0, "good", List.of(route(10)));
        Path blockedTemporary = Path.of(store.operations().fenceFile() + ".tmp");
        Files.createDirectory(blockedTemporary);
        assertThrows(IllegalStateException.class,
                () -> runtime.applyRoutes(1, "disk-full", List.of(route(20))));
        assertNull(runtime.operationOf("disk-full"));
        assertEquals(1, runtime.getRevision());
        assertEquals(10, store.loadOrNull().routes().get(0).getTargets().get(1).weight());
        Files.delete(blockedTemporary);
        assertEquals("APPLIED", runtime.applyRoutes(1, "disk-full", List.of(route(20))).status());
        runtime.close();
    }

    @Test
    void diskFullAtCommitCannotPublishCandidateAndQuarantinesUncertainStorage() {
        Path path = directory.resolve("full-at-commit.json");
        RouteOperationStore injected = new RouteOperationStore(path) {
            @Override protected void commitTransaction(Connection connection) throws SQLException {
                throw new SQLException("Injected disk full during transaction commit", "HY000", 90028);
            }
        };
        RouteOverlayStore overlay = new RouteOverlayStore(path) {
            @Override public RouteOperationStore operations() { return injected; }
        };
        GatewayRuntime runtime = runtime(overlay);
        assertThrows(IllegalStateException.class, () -> runtime.applyRoutes(0, "full", List.of(route(10))));
        assertEquals(0, runtime.getRevision());
        assertTrue(runtime.getRouteMatcher().listRoutes().isEmpty());
        assertThrows(IllegalStateException.class, overlay::loadOrNull,
                "见证已推进但提交失败时禁止自行回退或伪造未执行");
        assertThrows(IllegalStateException.class, () -> runtime.operationOf("full"));
        assertThrows(IllegalStateException.class, () -> runtime.applyRoutes(0, "new-id", List.of(route(20))));
        runtime.close();
    }

    @Test
    void restoredOldDatabaseOrMissingFenceFailsClosed() throws Exception {
        Path path = directory.resolve("backup.json");
        RouteOverlayStore store = new RouteOverlayStore(path);
        GatewayRuntime runtime = runtime(store);
        runtime.applyRoutes(0, "first", List.of(route(10)));
        Path backup = directory.resolve("backup.mv.db");
        Files.copy(store.operations().databaseFile(), backup);
        // 不推进路由版本的拒绝操作也必须受高水位保护。
        assertThrows(ManageApiException.class, () -> runtime.applyRoutes(0, "conflict", List.of(route(20))));
        Files.copy(backup, store.operations().databaseFile(), StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IllegalStateException.class, store::loadOrNull);
        assertThrows(IllegalStateException.class, () -> runtime.applyRoutes(1, "new", List.of(route(30))));
        Files.delete(store.operations().fenceFile());
        assertThrows(IllegalStateException.class, store::loadOrNull);
        runtime.close();
    }

    @Test
    void corruptDurableStateNeverFallsBackToOldJson() throws Exception {
        Path path = directory.resolve("corrupt.json");
        RouteOverlayStore store = new RouteOverlayStore(path);
        GatewayRuntime runtime = runtime(store);
        runtime.applyRoutes(0, "first", List.of(route(10)));
        Files.writeString(path, "{\"revision\":0,\"routes\":[]}");
        Files.writeString(store.operations().databaseFile(), "broken transaction file");
        assertThrows(IllegalStateException.class, store::loadOrNull);
        runtime.close();
    }

    @Test
    void legacyLatestOperationRemainsUnknownAndCannotBeReusedAfterMigration() {
        Path path = directory.resolve("legacy.json");
        RouteOverlayStore store = new RouteOverlayStore(path);
        store.save(8, "legacy", 1, List.of(route(10)));
        GatewayRuntime runtime = restart(path);
        assertNull(runtime.operationOf("legacy"));
        assertThrows(ManageApiException.class, () -> runtime.applyRoutes(8, "legacy", List.of(route(20))));
        runtime.applyRoutes(8, "new", List.of(route(20)));
        assertEquals(GatewayRuntime.OperationStatus.UNKNOWN, runtime.operationOf("legacy").status());
        assertThrows(ManageApiException.class, () -> runtime.applyRoutes(9, "legacy", List.of(route(30))));
        runtime.close();
    }

    @Test
    void delayedRequestAndRepeatedRejectionCannotRebaseItself() {
        Path path = directory.resolve("late.json");
        GatewayRuntime runtime = runtime(new RouteOverlayStore(path));
        runtime.applyRoutes(0, "manual", List.of(route(10)));
        assertThrows(ManageApiException.class, () -> runtime.applyRoutes(0, "late", List.of(route(20))));
        GatewayRuntime restarted = restart(path);
        assertEquals(GatewayRuntime.OperationStatus.CONFLICT, restarted.operationOf("late").status());
        assertMismatch(() -> restarted.applyRoutes(1, "late", List.of(route(20))));
        assertEquals(1, restarted.getRevision());
        runtime.close();
        restarted.close();
    }

    @Test
    void actualProcessCrashBeforeCommitStopsRecoveryAndAfterCommitRestoresReceipt() throws Exception {
        for (String phase : List.of("before", "after")) {
            Path path = directory.resolve("crash-" + phase + ".json");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m", "-XX:ActiveProcessorCount=2",
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CrashWriter.class.getName(), path.toString(), phase)
                    .redirectErrorStream(true).redirectOutput(directory.resolve(phase + ".log").toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "崩溃子进程必须有界退出");
                assertEquals(73, child.exitValue(), Files.readString(directory.resolve(phase + ".log")));
                RouteOverlayStore reopened = new RouteOverlayStore(path);
                if (phase.equals("before")) {
                    assertThrows(IllegalStateException.class, reopened::loadOrNull);
                } else {
                    assertEquals(1, reopened.loadOrNull().revision());
                    assertEquals("APPLIED", reopened.operations().find("crash").status());
                }
            } finally {
                if (child.isAlive()) { child.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }

    public static class CrashWriter {
        public static void main(String[] args) {
            Path path = Path.of(args[0]);
            RouteOperationStore store = new RouteOperationStore(path) {
                @Override protected void commitTransaction(Connection connection) throws SQLException {
                    if (args[1].equals("after")) { super.commitTransaction(connection); }
                    Runtime.getRuntime().halt(73);
                }
            };
            store.commit(new RouteOverlayStore.AppliedState(0, "", 0, List.of()),
                    new RouteOverlayStore.AppliedState(1, "crash", 1, List.of(route(10))),
                    new RouteOperationStore.Receipt("crash", "hash", "APPLIED", 1, "applied", 1, Long.MAX_VALUE, List.of(route(10))));
        }
    }

    private void assertMismatch(org.junit.jupiter.api.function.Executable operation) {
        ManageApiException failure = assertThrows(ManageApiException.class, operation);
        assertEquals(409, failure.getStatus().code());
        assertEquals("OPERATION_PARAMETER_MISMATCH", failure.getDetails().get("code"));
    }

    private GatewayRuntime restart(Path path) {
        RouteOverlayStore store = new RouteOverlayStore(path);
        var state = store.loadOrNull();
        GatewayRuntime runtime = runtime(store);
        runtime.restoreRoutesRevision(state.revision(), state.appliedOperationId());
        return runtime;
    }

    private GatewayRuntime runtime(RouteOverlayStore store) {
        DiscoverySettings discovery = new DiscoverySettings();
        discovery.setType(DiscoveryType.NAMESERVER);
        var state = store.loadOrNull();
        return new GatewayRuntime(0, state == null ? List.of() : state.routes(), 1000, 3000,
                new FilterSettings(), discovery, null, new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(directory.resolve("config-" + System.nanoTime() + ".json"))), "", store);
    }

    private static RouteConfig route(int weight) {
        RouteConfig route = new RouteConfig();
        route.setId("orders");
        route.setBusinessPrefix("/api/orders");
        route.setTargets(new ArrayList<>(List.of(new RouteTarget("demo", "v1", 100), new RouteTarget("demo", "v2", weight))));
        return route;
    }
}
