package com.rover.agent.runtime.llm;

import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用已配置的模型做目标结构化辅助：规则匹配不出对象时，让模型从候选清单里挑一个。
 *
 * 模型只做"选择"，不做"生成"：候选是现有路由 / 服务 / 实例的枚举，回复必须原样命中候选行，
 * 命中不了就当作没有把握。这样即使模型胡说，也不会把诊断带到不存在的对象上。
 *
 * 模型未配置、不可用或调用失败时一律返回空，由 {@link com.rover.agent.core.context.TargetResolver}
 * 回退到澄清提问——辅助能力缺失不能变成能力退化后的猜测。
 */
public final class ModelTargetInterpreter implements TargetInterpreter {

    private static final Logger log = LoggerFactory.getLogger(ModelTargetInterpreter.class);

    private static final String UNKNOWN = "UNKNOWN";
    private static final int MAX_QUERY_LENGTH = 500;

    private static final String SYSTEM_PROMPT = "你是 Rover 运维助手的调查对象识别器。"
            + "只能从候选清单里选出一个与用户问题最可能对应的对象，并把该候选行原样回复。"
            + "没有把握就只回复 " + UNKNOWN + "。不要解释、不要补充、不要编造清单外的对象。";

    private final ChatModelGateway gateway;

    public ModelTargetInterpreter(ChatModelGateway gateway) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
    }

    @Override
    public Optional<ResourceTarget> infer(String query, List<RouteSnapshot> routes, List<InstanceSnapshot> instances) {
        List<ResourceTarget> candidates = candidates(routes, instances);
        if (candidates.isEmpty() || query == null || query.isBlank() || !gateway.configured() || !gateway.available()) {
            return Optional.empty();
        }
        try {
            String answer = gateway.chatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user("候选对象：\n" + describe(candidates)
                            + "\n用户问题：" + truncate(query)
                            + "\n只回复一个候选行原样内容，或 " + UNKNOWN)
                    .call()
                    .content();
            return pick(candidates, answer);
        } catch (Exception ex) {
            log.warn("模型目标识别失败，转为请求澄清：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 候选清单：路由 → 服务 → 实例，去重后保持稳定顺序。 */
    private static List<ResourceTarget> candidates(List<RouteSnapshot> routes, List<InstanceSnapshot> instances) {
        Map<String, ResourceTarget> unique = new LinkedHashMap<>();
        if (routes != null) {
            for (RouteSnapshot route : routes) {
                String prefix = text(route.businessPrefix());
                if (!prefix.isBlank()) {
                    unique.putIfAbsent("ROUTE|" + prefix, ResourceTarget.route(prefix));
                }
                String service = text(route.serviceName());
                if (!service.isBlank()) {
                    unique.putIfAbsent("SERVICE|" + service, ResourceTarget.service(service));
                }
            }
        }
        if (instances != null) {
            for (InstanceSnapshot instance : instances) {
                String service = text(instance.serviceName());
                if (!service.isBlank()) {
                    unique.putIfAbsent("SERVICE|" + service, ResourceTarget.service(service));
                }
                String address = address(instance);
                if (!address.isBlank()) {
                    unique.putIfAbsent("INSTANCE|" + address, ResourceTarget.instance(address));
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private static String describe(List<ResourceTarget> candidates) {
        StringBuilder builder = new StringBuilder();
        for (ResourceTarget candidate : candidates) {
            builder.append('[').append(candidate.type()).append("] ").append(candidate.value()).append('\n');
        }
        return builder.toString();
    }

    /** 只在回复原样命中候选取值时采纳；除此之外（含 UNKNOWN 与自由发挥）都视为没有把握。 */
    private static Optional<ResourceTarget> pick(List<ResourceTarget> candidates, String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        String text = answer.trim().replaceAll("^[`\"']+|[`\"']+$", "").trim();
        if (text.isEmpty() || UNKNOWN.equalsIgnoreCase(text)) {
            return Optional.empty();
        }
        for (ResourceTarget candidate : candidates) {
            if (candidate.value().equalsIgnoreCase(text)
                    || ("[" + candidate.type() + "] " + candidate.value()).equalsIgnoreCase(text)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String address(InstanceSnapshot instance) {
        String host = text(instance.host());
        return host.isBlank() || instance.port() <= 0 ? "" : host + ":" + instance.port();
    }

    private static String truncate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= MAX_QUERY_LENGTH ? trimmed : trimmed.substring(0, MAX_QUERY_LENGTH);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}