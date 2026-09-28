package com.rover.agent.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.capability.AgentCapability;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 工具契约测试：把「模型能不能选对工具」这件事里<b>确定性、可回归</b>的部分固化成断言。
 *
 * <p>工具选择由模型根据 {@link Tool#description()} 完成，模型层的命中率不可在单测复现（见
 * {@code ScriptedConversationModel} 的说明）。但「描述写得够不够、参数有没有说明、每个能力是否都有暴露」
 * 是模型命中率的静态前提——这些是确定性事实，可以也必须测。
 *
 * <p>它守住的是「模型在 9 个工具里选对那一个」这一步的前提：选错的空间始终存在，而工具本身
 * 必须先是可选、可描述、可执行的。
 */
class OpsToolsContractTest {

    /** 每个能力必须暴露的入口工具（只取代表方法，ROUTE_QUERY 还有 listRoutes 是 getRoute 的别名）。 */
    private static final Map<AgentCapability, String> EXPECTED_TOOL = Map.of(
            AgentCapability.ROUTE_QUERY, "getRoute",
            AgentCapability.INSTANCE_QUERY, "listInstances",
            AgentCapability.GATEWAY_METRICS_QUERY, "getGatewayMetrics",
            AgentCapability.TRACE_QUERY, "getTraces",
            AgentCapability.CONFIG_READ, "getConfigs",
            AgentCapability.EVENT_QUERY, "listRegistryEvents",
            AgentCapability.LOG_QUERY, "queryLogs",
            AgentCapability.KNOWLEDGE_RETRIEVAL, "searchKnowledge");

    /** 工具描述最短长度：太短的描述装不下「何时用 / 何时不用」的判别信息，模型容易选错。 */
    private static final int MIN_DESCRIPTION_LENGTH = 60;

    /** 所有 @Tool 方法。 */
    private static List<Method> toolMethods() {
        return Arrays.stream(OpsTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();
    }

    @Test
    void toolCountMatchesTheDocumentedSet() {
        assertEquals(9, toolMethods().size(),
                "对外宣称的工具数量与实际不符：模型看到的工具集变了，文档与能力清单应同步");
    }

    @Test
    void everyCapabilityIsExposedByAtLeastOneTool() {
        List<String> toolNames = toolMethods().stream().map(Method::getName).toList();
        List<String> missing = EXPECTED_TOOL.entrySet().stream()
                .filter(entry -> !toolNames.contains(entry.getValue()))
                .map(entry -> entry.getKey() + " -> 缺工具 " + entry.getValue())
                .toList();
        assertTrue(missing.isEmpty(),
                "以下能力没有暴露给模型的工具，模型将无法选择它们：\n" + String.join("\n", missing));
    }

    @Test
    void everyToolDescriptionIsComplete() {
        List<String> incomplete = toolMethods().stream()
                .filter(method -> {
                    String description = method.getAnnotation(Tool.class).description();
                    return description == null || description.trim().length() < MIN_DESCRIPTION_LENGTH;
                })
                .map(method -> method.getName() + " 的描述过短（<" + MIN_DESCRIPTION_LENGTH + " 字）")
                .toList();
        assertTrue(incomplete.isEmpty(),
                "以下工具的描述过短，缺少「何时用 / 何时不用」的判别信息：\n" + String.join("\n", incomplete));
    }

    @Test
    void everyToolParamHasDescription() {
        List<String> missing = new java.util.ArrayList<>();
        for (Method method : toolMethods()) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(ToolParam.class)) {
                    String description = parameter.getAnnotation(ToolParam.class).description();
                    if (description == null || description.isBlank()) {
                        missing.add(method.getName() + " 的参数 " + parameter.getName() + " 缺少说明");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "以下工具参数缺少说明，模型不知道该传什么：\n" + String.join("\n", missing));
    }

    @Test
    void toolMethodNamesAreUnique() {
        List<String> names = toolMethods().stream().map(Method::getName).toList();
        assertFalse(names.stream().anyMatch(name -> java.util.Collections.frequency(names, name) > 1),
                "工具方法名出现重复");
    }
}
