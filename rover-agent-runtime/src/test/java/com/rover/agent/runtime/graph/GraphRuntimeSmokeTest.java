package com.rover.agent.runtime.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 Spring AI Alibaba Graph 能与本项目使用的 Spring AI 2.0.1 / Spring Boot 4.1.1 正常协同：
 * 图构建、节点顺序执行、共享状态读写，以及调查链依赖的列表累积策略都必须可用。
 */
class GraphRuntimeSmokeTest {

    @Test
    void runsNodeChainWithSharedState() throws Exception {
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("note", new ReplaceStrategy());
            strategies.put("healthy", new ReplaceStrategy());
            strategies.put("evidence", new AppendStrategy());
            strategies.put("branch", new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory)
                .addNode("route", AsyncNodeAction.node_async(
                        state -> Map.of("note", "路由已命中", "healthy", false,
                                "evidence", List.of("routes"), "branch", "instance")))
                .addNode("instance", AsyncNodeAction.node_async(
                        state -> Map.of("note", "实例维度已调查", "evidence", List.of("instances"))))
                .addNode("metrics", AsyncNodeAction.node_async(
                        state -> Map.of("note", "跳过实例分支", "evidence", List.of("metrics"))))
                .addEdge(StateGraph.START, "route")
                .addConditionalEdges("route", AsyncEdgeAction.edge_async(state -> state.value("branch", "")),
                        Map.of("instance", "instance", "metrics", "metrics"))
                .addEdge("instance", StateGraph.END)
                .addEdge("metrics", StateGraph.END);

        CompiledGraph compiled = graph.compile();
        OverAllState result = compiled.invoke(Map.of("note", "开始")).orElseThrow();

        assertEquals("实例维度已调查", result.value("note", ""));
        assertEquals(Boolean.FALSE, result.value("healthy", true));
        // AppendStrategy 必须把节点返回的列表逐元素并入既有列表，调查链的证据累积依赖该语义。
        assertEquals(List.of("routes", "instances"), result.<List<String>>value("evidence", List.of()));
    }
}