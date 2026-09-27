package com.rover.agent.core.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import com.rover.agent.core.model.ResourceTarget;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 规则意图分类：验收 Case 里的六类问法都要落到正确意图与口径上。 */
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    void classifiesMetricStateQueryWithoutTarget() {
        IntentDecision decision = classifier.classify("网关现在QPS多少？");

        assertEquals(AgentIntent.QUERY_STATE, decision.intent());
        assertEquals(QuerySubject.METRIC, IntentClassifier.stateSubject("网关现在QPS多少？"));
        // 全局指标查询不点名对象：目标解析不参与，也不会因此要求用户补路径。
        assertTrue(decision.targetHint().isBlank());
    }

    @Test
    void classifiesInvestigationWithPathHint() {
        IntentDecision decision = classifier.classify("为什么 /api/demo/tt 调用失败？");

        assertEquals(AgentIntent.INVESTIGATE, decision.intent());
        assertEquals("/api/demo/tt", decision.targetHint());
    }

    @Test
    void classifiesCapabilityQuestionAsCapabilitiesTopic() {
        IntentDecision decision = classifier.classify("你现在能做什么？");

        assertEquals(AgentIntent.EXPLAIN, decision.intent());
        assertEquals(IntentTopic.CAPABILITIES, decision.topic());
        assertTrue(decision.targetHint().isBlank());
    }

    @Test
    void classifiesActionRequestWithRecognisedAction() {
        IntentDecision decision = classifier.classify("把order-03摘掉。");

        assertEquals(AgentIntent.ACTION_REQUEST, decision.intent());
        assertEquals(ActionType.DRAIN_INSTANCE, decision.requestedAction());
        assertEquals("order-03", decision.targetHint());
        assertFalse(decision.needsClarification());
    }

    @Test
    void classifiesScheduledInspectionInsteadOfInvestigation() {
        IntentDecision decision = classifier.classify("每天9点自动巡检订单服务并发邮件。");

        assertEquals(AgentIntent.CREATE_INSPECTION, decision.intent());
        // 这类请求里即便出现对象线索（「订单服务」），执行形态也由意图决定，不会被当成一次调查。
        assertFalse(decision.needsClarification());
    }

    /**
     * 意图与目标分离：判断「想干什么」只看文本，不依赖任何对象是否真实存在。
     *
     * 结构上也一样——{@link IntentDecision} 没有「已解析目标」字段，目标线索只是解析阶段的输入。
     */
    @Test
    void intentJudgementIsIndependentFromTargetResolution() {
        IntentDecision decision = classifier.classify("order-service现在有几个健康实例？");

        assertEquals(AgentIntent.QUERY_STATE, decision.intent());
        assertEquals("order-service", decision.targetHint());
        assertEquals(QuerySubject.INSTANCE, IntentClassifier.stateSubject("order-service现在有几个健康实例？"));
        assertFalse(Arrays.stream(IntentDecision.class.getRecordComponents())
                .anyMatch(component -> component.getType() == ResourceTarget.class));
    }

    @Test
    void investigationWordsWinOverStateWords() {
        // 同时含「有没有」（状态）与「问题」（调查）：按调查处理，交给目标解析决定要不要澄清。
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("帮我看看最近有没有问题").intent());
    }

    @Test
    void routeSubjectIsRecognisedSeparatelyFromInvestigation() {
        assertEquals(QuerySubject.ROUTE, IntentClassifier.stateSubject("/api/demo/tt 命中了哪条路由？"));
        assertEquals(QuerySubject.NONE, IntentClassifier.stateSubject("在吗？"));
    }

    @Test
    void selfIntroductionQuestionsAreAnsweredAsCapabilities() {
        // 「你是谁 / 介绍一下你自己 / help」与「你能做什么」同义：都该由注册表回答，不进目标解析。
        assertEquals(IntentTopic.CAPABILITIES, classifier.classify("你是谁？").topic());
        assertEquals(IntentTopic.CAPABILITIES, classifier.classify("介绍一下你自己").topic());
        assertEquals(IntentTopic.CAPABILITIES, classifier.classify("help").topic());
        assertEquals(IntentTopic.CAPABILITIES, classifier.classify("你是干什么的？").topic());
    }

    @Test
    void plainChatStaysUnknownSoRuntimeCanIntroduceItself() {
        // 闲聊没有可执行意图：保持 UNKNOWN，由运行层回自我介绍，而不是被追问「请给出请求路径」。
        assertEquals(AgentIntent.UNKNOWN, classifier.classify("你好，今天天气怎么样？").intent());
        assertEquals(AgentIntent.UNKNOWN, classifier.classify("谢谢！").intent());
        assertEquals(AgentIntent.UNKNOWN, classifier.classify("哈哈哈").intent());
    }

    @Test
    void colloquialFailurePhrasingsStillBecomeInvestigation() {
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("order-service 好像不对劲").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("/api/demo/tt 崩了怎么办？").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("实例怎么起不来了").intent());
    }

    /**
     * 同义改写回归基线：同一件事换十种说法，规则层都要落到同一个意图上。
     *
     * 这组语料就是「用户换个说法就匹配不到」的度量基准——找模型之前先要看确定性的那一层漏在哪，
     * 否则改提示词只是把漏网之鱼从规则层推到模型层，问题并不会消失。
     */
    @Test
    void paraphrasesOfTheSameQuestionLandOnTheSameIntent() {
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("接口响应好慢").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("order-service 咋回事啊").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("网关是不是扛不住了").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("/api/pay 一直 502").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("实例好像掉线了").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("服务连不上，页面一直转圈").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("这个接口时好时坏").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("order-service 出问题了吧").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("实例无响应了").intent());
        assertEquals(AgentIntent.INVESTIGATE, classifier.classify("响应超时是什么原因").intent());

        assertEquals(AgentIntent.QUERY_STATE, classifier.classify("网关现在QPS多少？").intent());
        assertEquals(AgentIntent.QUERY_STATE, classifier.classify("order-service 现在有几个健康实例？").intent());
        assertEquals(AgentIntent.QUERY_STATE, classifier.classify("/api/order 现在指向哪个服务？").intent());
        assertEquals(AgentIntent.QUERY_STATE, classifier.classify("限流阈值配的是多少").intent());

        assertEquals(AgentIntent.ACTION_REQUEST, classifier.classify("把 order-03 摘掉").intent());
        assertEquals(AgentIntent.ACTION_REQUEST, classifier.classify("帮我把 /api/pay 的超时改成 3 秒").intent());
        assertEquals(AgentIntent.ACTION_REQUEST, classifier.classify("把 order-04 重新上线").intent());

        assertEquals(AgentIntent.CREATE_INSPECTION, classifier.classify("每天 9 点自动巡检订单服务并发邮件").intent());
        assertEquals(AgentIntent.UNKNOWN, classifier.classify("你好，今天天气怎么样？").intent());
        assertEquals(AgentIntent.UNKNOWN, classifier.classify("帮我写一首诗").intent());
    }

    /**
     * 「上下线」是名词短语，问的是变更经过，不是要求执行摘除。
     *
     * 动作词表按子串命中，「上下线」会撞上「下线」；一旦被当成处置请求，用户拿到的是
     * 一份「不执行的处置计划」，而他要的其实是一份注册事件清单。
     */
    @Test
    void instanceUpAndDownHistoryIsAnEventQueryNotADrainRequest() {
        IntentDecision decision = classifier.classify("最近有哪些实例上下线？");

        assertEquals(AgentIntent.QUERY_STATE, decision.intent());
        assertEquals(ActionType.UNKNOWN, decision.requestedAction());
        assertEquals(QuerySubject.EVENT, IntentClassifier.stateSubject("最近有哪些实例上下线？"));
    }
}