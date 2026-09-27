package com.rover.agent.core.intent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 意图识别的评测闭环：用带标签的语料把「规则层到底能判对多少」变成一个可量化、可回归的数字。
 *
 * <p>为什么需要它：意图识别的改动（改词表、改判断顺序、改提示词）此前只能靠零散断言验证，
 * 一旦某次微调把一类说法判错，只有恰好有人问到才会暴露。这里把语料固化成评测集——
 * <b>改词表就是改进这个数字，改坏了这个测试立刻红</b>，提示词优化的效果也能在同一把尺子上量。
 *
 * <p>语料分两层，对应意图识别的两层实现：
 * <ul>
 *   <li>{@link #RULE_CORPUS}：规则层「该判对」的说法，要求逐条精确命中，是确定性底线；</li>
 *   <li>{@link #AMBIGUOUS_PHRASINGS}：规则层刻意不拍板的模糊说法，要求规则不得给出高置信度结论——
 *       这正是模型补位的入口，一旦规则把这类问题高置信度抢走，模型就再没机会纠正。</li>
 * </ul>
 *
 * <p>模型层的命中率不在单测里断言：真实模型调用不可复现，把它写进单测只会得到要么随机红、
 * 要么形同虚设的断言。模型路径的评测放在带外（同一份语料可作为评测输入复用）。
 */
class IntentEvaluationTest {

    /** 一条评测样本：用户原话 + 期望意图。 */
    private record Sample(String question, AgentIntent expected) { }

    /**
     * 规则层语料：覆盖全部意图类型，且刻意包含口语化与同义改写——
     * 真实用户不会按词表说话，语料必须比词表更「脏」才有评测价值。
     */
    private static final List<Sample> RULE_CORPUS = List.of(
            // 故障调查：书面问法与口语问法混排
            new Sample("为什么 /api/demo/tt 调用失败？", AgentIntent.INVESTIGATE),
            new Sample("order-service 最近老是超时", AgentIntent.INVESTIGATE),
            new Sample("网关 502 是不是有问题", AgentIntent.INVESTIGATE),
            new Sample("/api/demo/tt 返回 503 是什么原因", AgentIntent.INVESTIGATE),
            new Sample("demo-service 崩了", AgentIntent.INVESTIGATE),
            new Sample("服务起不来了", AgentIntent.INVESTIGATE),
            new Sample("实例掉线了", AgentIntent.INVESTIGATE),
            new Sample("接口访问不了", AgentIntent.INVESTIGATE),
            new Sample("最近请求抖动得厉害", AgentIntent.INVESTIGATE),
            new Sample("帮我看看是不是雪崩了", AgentIntent.INVESTIGATE),
            new Sample("这条路由怎么回事", AgentIntent.INVESTIGATE),

            // 状态查询：问「现在是多少 / 有几个 / 命中哪条」
            new Sample("网关现在 QPS 多少？", AgentIntent.QUERY_STATE),
            new Sample("order-service 有几个健康实例？", AgentIntent.QUERY_STATE),
            new Sample("现在有几条路由", AgentIntent.QUERY_STATE),
            new Sample("最近有哪些实例上下线", AgentIntent.QUERY_STATE),
            new Sample("网关的采样率是多少", AgentIntent.QUERY_STATE),
            new Sample("实例注册了几个节点", AgentIntent.QUERY_STATE),
            new Sample("order-service 在哪条路由上", AgentIntent.QUERY_STATE),
            new Sample("网关的限流阈值配的多少", AgentIntent.QUERY_STATE),

            // 处置请求：只有读到明确动作才算，不靠猜测
            new Sample("帮我摘掉 demo-service 的 127.0.0.1:8081 这个实例", AgentIntent.ACTION_REQUEST),
            new Sample("把这条路由的超时改成 3 秒", AgentIntent.ACTION_REQUEST),
            new Sample("重启 demo-service", AgentIntent.ACTION_REQUEST),
            new Sample("把 /api/demo/tt 下线", AgentIntent.ACTION_REQUEST),

            // 定时巡检
            new Sample("帮我给 order-service 加一个每天上午的巡检", AgentIntent.CREATE_INSPECTION),
            new Sample("每天早上检查一下各实例健康", AgentIntent.CREATE_INSPECTION),

            // 解释与能力咨询
            new Sample("你能做什么？", AgentIntent.EXPLAIN),
            new Sample("介绍一下你自己", AgentIntent.EXPLAIN),
            new Sample("总结一下这次故障", AgentIntent.EXPLAIN),

            // 知识检索
            new Sample("怎么接入这个网关", AgentIntent.KNOWLEDGE_QUERY),
            new Sample("网关的文档在哪里", AgentIntent.KNOWLEDGE_QUERY),

            // 与运维无关的闲聊：必须如实落到 UNKNOWN，不能硬套一个意图
            new Sample("今天天气怎么样", AgentIntent.UNKNOWN),
            new Sample("你好", AgentIntent.UNKNOWN));

    /**
     * 规则层不拍板的模糊说法：这些句子里没有可靠的关键词证据，规则只能给中/低置信度或 UNKNOWN。
     *
     * 它们的存在是「模型补位」的前提：{@code IntentService} 只在规则非高置信度时才问模型，
     * 因此这里一旦出现高置信度结论，模型路径就被规则提前截断了。
     */
    private static final List<String> AMBIGUOUS_PHRASINGS = List.of(
            "网关现在怎么样",
            "demo-service 有点卡",
            "order-service 是不是不太行了",
            "最近服务不太正常",
            "网关最近有点顶不住",
            "这个服务跟昨天比怎么样");

    @Test
    void ruleLayerMatchesTheLabeledCorpus() {
        List<String> mismatches = new ArrayList<>();
        for (Sample sample : RULE_CORPUS) {
            IntentDecision decision = new IntentClassifier().classify(sample.question());
            if (decision.intent() != sample.expected()) {
                mismatches.add("「" + sample.question() + "」期望 " + sample.expected()
                        + "，实际 " + decision.intent() + "（置信度 " + decision.confidence()
                        + "，依据：" + decision.reason() + "）");
            }
        }
        assertTrue(mismatches.isEmpty(),
                "规则意图识别有 " + mismatches.size() + "/" + RULE_CORPUS.size()
                        + " 条与语料不符（改词表或判断顺序后请确认这是有意为之，并同步更新语料）：\n"
                        + String.join("\n", mismatches));
    }

    @Test
    void ambiguousPhrasingsAreLeftToTheModel() {
        List<String> hijacked = new ArrayList<>();
        for (String question : AMBIGUOUS_PHRASINGS) {
            IntentDecision decision = new IntentClassifier().classify(question);
            if (decision.confidence() == Confidence.HIGH && decision.intent() != AgentIntent.UNKNOWN) {
                hijacked.add("「" + question + "」被规则高置信度判为 " + decision.intent());
            }
        }
        assertTrue(hijacked.isEmpty(),
                "以下模糊说法被规则高置信度拍板，模型将没有机会补位：\n" + String.join("\n", hijacked));
    }
}
