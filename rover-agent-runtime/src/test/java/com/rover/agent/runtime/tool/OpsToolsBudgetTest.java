package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.port.ConfigReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 对话主路径的调用预算：文档与配置都声明「单次任务 30 次工具调用」，这里把它固定成断言。
 *
 * <p>为什么值得测：预算只在 {@code OpsTools.withinBudget()} 里体现，一旦失效，表现是模型反复取数停不下来——
 * 而它自己不会停，只能靠这个上限兜住。反之上限太小也会让「一句话问三件事」在取数途中被掐断，
 * 因此这里同时断言「预算内每次都真的取数」，两侧都不能漂移。
 *
 * <p>预算是任务级总量而不是每个工具各自一份：换工具不应重置额度，这也是模型会去钻的缝。
 */
class OpsToolsBudgetTest {

    /** 与 {@code OpsTools.MAX_CALLS} 及 {@code spring.ai.tools.limits.max-total-tool-calls} 对齐。 */
    private static final int EXPECTED_BUDGET = 30;

    private final CountingRoutes routes = new CountingRoutes();

    private final CountingConfigs configs = new CountingConfigs();

    @Test
    void budgetStopsCollectionAtThirtyCalls() {
        OpsTools tools = opsTools();

        for (int index = 0; index < EXPECTED_BUDGET; index++) {
            assertFalse(tools.listRoutes().contains("已达上限"),
                    "第 " + (index + 1) + " 次取数仍在预算内，不应被拦下");
        }
        assertEquals(EXPECTED_BUDGET, routes.calls(), "预算内的每次调用都应真实取数一次");

        String blocked = tools.listRoutes();
        assertTrue(blocked.contains("已达上限"), "超出预算必须明确让模型收尾，不能静默丢弃");
        assertEquals(EXPECTED_BUDGET, routes.calls(), "预算耗尽后不应再产生任何取数");
    }

    @Test
    void budgetIsSharedAcrossToolsNotPerTool() {
        OpsTools tools = opsTools();

        for (int index = 0; index < EXPECTED_BUDGET / 2; index++) {
            tools.listRoutes();
            tools.getConfigs();
        }
        assertEquals(EXPECTED_BUDGET, routes.calls() + configs.calls(), "两个工具各调一半也应恰好耗尽预算");

        assertTrue(tools.listRoutes().contains("已达上限"), "预算是任务级总量，换工具不应重置额度");
        assertTrue(tools.getConfigs().contains("已达上限"));
        assertEquals(EXPECTED_BUDGET, routes.calls() + configs.calls(), "被拦下的调用同样不再取数");
    }

    private OpsTools opsTools() {
        CapabilityExecutor executor = new CapabilityExecutor(routes, null, null, null, configs, null, null, null,
                CapabilityRegistry.standard());
        InvestigationTask task = new InvestigationTaskRegistry()
                .register("session-budget-" + System.nanoTime(), "预算测试");
        return new OpsTools(executor, task);
    }

    /** 计数的路由端口：既提供空事实，也用来证明「预算耗尽后到底有没有再取数」。 */
    private static final class CountingRoutes implements RouteReadPort {

        private final AtomicInteger calls = new AtomicInteger();

        private int calls() {
            return calls.get();
        }

        @Override
        public List<RouteSnapshot> routes() {
            calls.incrementAndGet();
            return List.of();
        }

        @Override
        public DiscoveryMode discoveryMode() {
            return DiscoveryMode.UNKNOWN;
        }
    }

    /** 计数的配置端口：与路由端口一起证明「额度是任务级共享的」。 */
    private static final class CountingConfigs implements ConfigReadPort {

        private final AtomicInteger calls = new AtomicInteger();

        private int calls() {
            return calls.get();
        }

        @Override
        public List<ConfigEntrySnapshot> configs() {
            calls.incrementAndGet();
            return List.of();
        }
    }
}
