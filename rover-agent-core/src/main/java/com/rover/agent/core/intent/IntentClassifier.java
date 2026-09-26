package com.rover.agent.core.intent;

import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import com.rover.agent.core.model.TimeRange;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图分类：不依赖模型的确定性判断，是意图识别的底线。
 *
 * 关键词命中只是「证据」，因此判断顺序被刻意固定成从「最容易被误判」到「最宽泛」：
 * 定时巡检 → 处置请求 → 能力咨询 → 解释/总结 → 故障调查 → 状态查询 → 知识检索 → 未知。
 * 例如「帮我看看最近有没有问题」同时含有状态查询词「有没有」与调查词「问题」，
 * 因为调查优先，它会走澄清而不是被当成一次全局指标查询。
 *
 * 分类只判断「想干什么」：{@code targetHint} 只是从文本里抽出的对象线索，
 * 是否真的存在该对象由目标解析（读真实注册数据）决定，本类绝不因为「文本里像」就认定目标。
 */
public final class IntentClassifier {

    /** 与目标解析共用同一套路径口径：以 / 开头，允许字母数字与常见路径字符。 */
    private static final Pattern PATH_TOKEN = Pattern.compile("/[A-Za-z0-9][A-Za-z0-9._~/-]*");

    private static final Pattern INSTANCE_ADDRESS = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5}\\b");

    /** 资源名线索：必须含连字符或下划线，避免把普通英文单词当成服务名。 */
    private static final Pattern NAMED_RESOURCE = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+");

    private static final Pattern RECENT_WINDOW = Pattern.compile("(?:最近|近|过去)\\s*(\\d+)\\s*(分钟|小时|天)");

    private static final List<String> INSPECTION_WORDS = List.of("巡检");
    private static final List<String> SCHEDULE_WORDS = List.of("定时", "每天", "每周", "每小时", "每天上午", "每天下午", "定期");
    private static final List<String> INSPECTION_OBJECTS = List.of("检查", "报告", "邮件", "通知", "监控", "巡检");

    private static final List<String> ACTION_WORDS = List.of("摘掉", "摘除", "下线", "上线", "重启", "扩容", "缩容",
            "移除", "去掉", "禁用", "启用", "停用", "改成", "设为", "设置为", "调整为", "修改成",
            "帮我摘", "帮我停", "帮我启", "帮我改", "帮我调", "帮我重启", "踢掉");

    private static final List<String> CAPABILITY_WORDS = List.of("你能做什么", "你能干什么", "你会什么", "你能帮我做什么",
            "有什么能力", "有哪些能力", "能做什么", "能做哪些", "支持哪些能力", "能力清单", "你是谁", "怎么用你",
            "自我介绍", "介绍一下你", "介绍一下你自己", "你是干什么的", "你是做什么的", "你是干嘛的", "能干啥",
            "能干点啥", "能做啥", "可以做什么", "有什么功能", "有哪些功能", "能帮我干什么", "怎么使用你",
            "如何使用你", "怎么用这个系统", "有什么用处", "有什么用", "who are you", "what can you do", "help");

    private static final List<String> EXPLAIN_WORDS = List.of("总结", "解释", "说明一下", "是什么意思", "什么意思",
            "展开说说", "详细说说", "这么判断", "这样判断", "依据是什么", "结论的依据", "怎么得出的", "怎么判断的");

    private static final List<String> INVESTIGATION_WORDS = List.of("为什么", "失败", "报错", "异常", "错误", "问题",
            "慢", "超时", "不通", "打不开", "没反应", "挂了", "不可用", "503", "排查", "调查", "诊断", "原因",
            "怎么回事", "怎么了", "咋回", "怎么办", "咋办", "不对劲", "不正常", "有毛病", "崩了", "炸了",
            "宕机", "卡顿", "抖动", "掉线", "起不来");

    private static final List<String> STATE_WORDS = List.of("多少", "几个", "数量", "状态", "有没有", "是否", "qps",
            "流量", "请求量", "请求数", "吞吐", "拒绝", "错误率", "5xx", "健康", "在线", "注册", "路由", "指向",
            "前缀", "配置", "采样率");

    private static final List<String> KNOWLEDGE_WORDS = List.of("怎么配置", "如何配置", "怎么设置", "如何设置", "怎么用",
            "如何使用", "怎么接入", "如何接入", "文档", "教程");

    private static final List<String> INSTANCE_WORDS = List.of("实例", "健康", "注册", "在线", "节点");

    private static final List<String> METRIC_WORDS = List.of("qps", "流量", "请求量", "请求数", "吞吐", "拒绝", "错误率",
            "5xx", "指标", "延迟", "响应时间");

    private static final List<String> ROUTE_WORDS = List.of("路由", "指向", "前缀", "转发", "匹配", "网关配置");

    private static final List<String> CONFIG_WORDS = List.of("配置", "限流", "熔断", "阈值", "限速", "并发", "采样率",
            "超时");

    /**
     * 事件词表刻意避开「注册」这一类实例词：问「注册实例有几个」问的是当前状态，
     * 只有明确提到「事件 / 上下线 / 剔除 / 注销」才是问变更经过。
     */
    private static final List<String> EVENT_WORDS = List.of("事件", "变更记录", "上下线", "剔除", "注销", "注册记录");

    private static final List<String> INCIDENT_CONTEXT_WORDS = List.of("这次", "当前", "刚才", "上一轮", "这个故障",
            "这个问题", "本次", "事件");

    /** 对一条用户消息做规则意图分类；结果永远非空（识别不出时为 UNKNOWN）。 */
    public IntentDecision classify(String question) {
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) {
            return IntentDecision.clarify(AgentIntent.UNKNOWN, "消息为空", "请描述要查询或调查的对象。");
        }
        String hint = targetHint(text);
        TimeRange timeRange = timeRange(text);
        if (mentionsInspection(text)) {
            return new IntentDecision(AgentIntent.CREATE_INSPECTION, Confidence.HIGH, IntentTopic.NONE, hint, timeRange,
                    ActionType.UNKNOWN, "问题描述了定时或周期性的巡检需求", false, null);
        }
        if (containsAny(text, ACTION_WORDS)) {
            ActionType action = actionType(text);
            return new IntentDecision(AgentIntent.ACTION_REQUEST, Confidence.HIGH, IntentTopic.NONE, hint, timeRange,
                    action, "问题要求对目标执行处置动作（" + action + "）", false, null);
        }
        if (containsAny(text, CAPABILITY_WORDS)) {
            return new IntentDecision(AgentIntent.EXPLAIN, Confidence.HIGH, IntentTopic.CAPABILITIES, hint, timeRange,
                    ActionType.UNKNOWN, "问题在询问系统具备哪些能力", false, null);
        }
        if (containsAny(text, EXPLAIN_WORDS)) {
            IntentTopic topic = containsAny(text, INCIDENT_CONTEXT_WORDS) ? IntentTopic.INCIDENT : IntentTopic.GENERAL;
            return new IntentDecision(AgentIntent.EXPLAIN, Confidence.MEDIUM, topic, hint, timeRange,
                    ActionType.UNKNOWN, "问题要求解释或总结已有信息", false, null);
        }
        if (containsAny(text, INVESTIGATION_WORDS)) {
            return new IntentDecision(AgentIntent.INVESTIGATE, Confidence.MEDIUM, IntentTopic.NONE, hint, timeRange,
                    ActionType.UNKNOWN, "问题在追问失败或异常的原因", false, null);
        }
        if (containsAny(text, STATE_WORDS)) {
            return new IntentDecision(AgentIntent.QUERY_STATE, Confidence.MEDIUM, IntentTopic.NONE, hint, timeRange,
                    ActionType.UNKNOWN, "问题在询问当前状态", false, null);
        }
        if (containsAny(text, KNOWLEDGE_WORDS)) {
            return new IntentDecision(AgentIntent.KNOWLEDGE_QUERY, Confidence.LOW, IntentTopic.GENERAL, hint, timeRange,
                    ActionType.UNKNOWN, "问题在询问文档或使用方式", false, null);
        }
        return new IntentDecision(AgentIntent.UNKNOWN, Confidence.LOW, IntentTopic.NONE, hint, timeRange,
                ActionType.UNKNOWN, "未能从问题文本识别出意图", false, null);
    }

    /**
     * 状态查询问的是哪一类事实。
     *
     * 与意图分类分开：这里只回答「问什么」，由查询执行侧决定用哪个只读能力取数。
     * 判定顺序为指标 → 配置 → 事件 → 实例 → 路由：越具体的口径越先判定，避免「限流阈值」被
     * 更宽泛的实例词抢走。
     */
    public static QuerySubject stateSubject(String question) {
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) {
            return QuerySubject.NONE;
        }
        if (containsAny(text, METRIC_WORDS)) {
            return QuerySubject.METRIC;
        }
        if (containsAny(text, CONFIG_WORDS)) {
            return QuerySubject.CONFIG;
        }
        if (containsAny(text, EVENT_WORDS)) {
            return QuerySubject.EVENT;
        }
        if (containsAny(text, INSTANCE_WORDS)) {
            return QuerySubject.INSTANCE;
        }
        if (containsAny(text, ROUTE_WORDS)) {
            return QuerySubject.ROUTE;
        }
        return QuerySubject.NONE;
    }

    /** 从问题文本里抽出对象线索：请求路径优先，其次实例地址，最后是带连字符的资源名。 */
    public static String targetHint(String question) {
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) {
            return "";
        }
        String path = longestMatch(PATH_TOKEN, text);
        if (!path.isBlank()) {
            return trimTrailingSlash(path);
        }
        String address = longestMatch(INSTANCE_ADDRESS, text);
        if (!address.isBlank()) {
            return address;
        }
        return longestMatch(NAMED_RESOURCE, text);
    }

    /** 处置动作类型：按关键词判断，判断不出时为 UNKNOWN（仍然出计划，只是不假设具体动作）。 */
    public static ActionType actionType(String question) {
        String text = question == null ? "" : question.trim();
        if (containsAny(text, List.of("超时", "timeout", "延时", "读超时", "连接超时"))) {
            return ActionType.UPDATE_ROUTE_TIMEOUT;
        }
        if (containsAny(text, List.of("限流", "熔断", "并发", "阈值", "限速"))) {
            return ActionType.UPDATE_RATE_LIMIT;
        }
        if (containsAny(text, List.of("恢复", "上线", "启用", "重启", "扩容"))) {
            return ActionType.RESTORE_INSTANCE;
        }
        if (containsAny(text, List.of("摘掉", "摘除", "下线", "移除", "去掉", "停用", "缩容", "踢掉"))) {
            return ActionType.DRAIN_INSTANCE;
        }
        return ActionType.UNKNOWN;
    }

    /** 时间范围：识别「最近 N 分钟/小时/天」，其余情况按未指定处理。 */
    public static TimeRange timeRange(String question) {
        String text = question == null ? "" : question.trim();
        Matcher matcher = RECENT_WINDOW.matcher(text);
        if (!matcher.find()) {
            return TimeRange.unspecified();
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return TimeRange.unspecified();
        }
        if (amount <= 0 || amount > 100000) {
            return TimeRange.unspecified();
        }
        long millis = switch (matcher.group(2)) {
            case "小时" -> TimeUnit.HOURS.toMillis(amount);
            case "天" -> TimeUnit.DAYS.toMillis(amount);
            default -> TimeUnit.MINUTES.toMillis(amount);
        };
        long now = System.currentTimeMillis();
        return new TimeRange(now - millis, now);
    }

    private static boolean mentionsInspection(String text) {
        if (containsAny(text, INSPECTION_WORDS)) {
            return true;
        }
        return containsAny(text, SCHEDULE_WORDS) && containsAny(text, INSPECTION_OBJECTS);
    }

    private static String longestMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String best = "";
        while (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private static String trimTrailingSlash(String token) {
        String trimmed = token;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}