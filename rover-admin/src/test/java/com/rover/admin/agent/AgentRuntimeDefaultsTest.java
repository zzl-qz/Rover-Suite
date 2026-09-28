package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.runtime.tool.OpsTools;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 运行参数默认值的契约测试：把「文档里写着的默认数字」与 application.yml / 代码常量钉在一起。
 *
 * <p>为什么值得测：这些数字分散在三处——文档表格、{@code application.yml}、代码常量。
 * 改了其中一处而忘了另外两处，症状不会立刻暴露（服务照常启动），只会在某次调查被提前掐断、
 * 或证据被悄悄清掉时才浮现。这里让三处不一致直接变红。
 */
class AgentRuntimeDefaultsTest {

    private final Map<String, Object> yaml = loadApplicationYaml();

    @Test
    void toolCallBudgetIsAlignedOnBothSides() throws Exception {
        int total = number("spring", "ai", "tools", "limits", "max-total-tool-calls").intValue();
        int perTool = number("spring", "ai", "tools", "limits", "max-calls-per-tool-default").intValue();

        assertEquals(30, total, "单次任务的工具调用总预算（与 OpsTools.MAX_CALLS 同值）");
        assertEquals(10, perTool, "该预算内单个工具的调用上限");

        Field maxCalls = OpsTools.class.getDeclaredField("MAX_CALLS");
        maxCalls.setAccessible(true);
        assertEquals(total, maxCalls.getInt(null),
                "OpsTools.MAX_CALLS 必须与 spring.ai.tools.limits.max-total-tool-calls 一致："
                        + "配置小了会在取数途中被掐断，代码小了则省下的额度永远用不到");
    }

    @Test
    void recordStoreRetentionAndQueueDefaultsMatchTheDocs() {
        assertEquals(30, number("rover", "admin", "log-retention-days").intValue(),
                "诊断证据类保留天数");
        assertEquals(3, number("rover", "admin", "log-telemetry-retention-days").intValue(),
                "遥测类保留天数：量大，短保留防膨胀");
        assertEquals(30, number("rover", "admin", "log-collect-interval-seconds").intValue(),
                "遥测采集间隔（秒）");
        assertTrue(number("rover", "admin", "log-collect-interval-seconds").intValue() >= 5,
                "采集间隔下限为 5 秒");

        assertEquals(8192, number("rover", "admin", "log-queue-capacity").intValue(),
                "遥测队列容量（best-effort，满则丢弃）");
        assertEquals(16384, number("rover", "admin", "log-critical-capacity").intValue(),
                "诊断证据队列容量（满则短暂等待，尽量不丢）");
        assertTrue(number("rover", "admin", "log-critical-capacity").intValue()
                        > number("rover", "admin", "log-queue-capacity").intValue(),
                "高优队列必须比普通队列更能装，否则「尽量不丢」没有意义");
    }

    private Number number(String... path) {
        Object current = yaml;
        for (String key : path) {
            assertTrue(current instanceof Map, "application.yml 缺少路径 " + String.join(".", path));
            current = ((Map<?, ?>) current).get(key);
        }
        assertTrue(current instanceof Number || current instanceof String,
                "application.yml 的 " + String.join(".", path) + " 不是数字：" + current);
        return current instanceof Number value ? value : Long.valueOf(String.valueOf(current).trim());
    }

    private static Map<String, Object> loadApplicationYaml() {
        try (InputStream in = AgentRuntimeDefaultsTest.class.getResourceAsStream("/application.yml")) {
            assertTrue(in != null, "classpath 下未找到 application.yml");
            Object loaded = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            return map;
        } catch (Exception ex) {
            throw new IllegalStateException("读取 application.yml 失败", ex);
        }
    }
}
