package com.rover.agent.runtime.llm;

import com.rover.agent.core.capability.UntrustedText;
import com.rover.agent.core.context.TargetInterpreter;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.util.Texts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过模型从现有路由、服务和实例候选中选择目标，输出须匹配候选。
 * 未配置、不可用或失败时返回空；按场景限制超时，仅在超时后重试一次。
 */
public final class ModelTargetInterpreter implements TargetInterpreter {

    private static final Logger log = LoggerFactory.getLogger(ModelTargetInterpreter.class);

    private static final String UNKNOWN = "UNKNOWN";
    private static final int MAX_QUERY_LENGTH = 500;

    private static final String SYSTEM_PROMPT = "你是 Rover 运维助手的调查对象识别器。"
            + "只能从候选清单里选出一个与用户问题最可能对应的对象，并把该候选行原样回复。"
            + "没有把握就只回复 " + UNKNOWN + "。不要解释、不要补充、不要编造清单外的对象。"
            + UntrustedText.contract();

    private final ChatModelGateway gateway;
    private final int timeoutSeconds;

    /**
     * @param timeoutSeconds 场景超时上限（秒）；0 表示用模型配置里的超时
     */
    public ModelTargetInterpreter(ChatModelGateway gateway, int timeoutSeconds) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
    }

    @Override
    public Optional<ResourceTarget> infer(String query, List<RouteSnapshot> routes, List<InstanceSnapshot> instances) {
        List<ResourceTarget> candidates = candidates(routes, instances);
        if (candidates.isEmpty() || query == null || query.isBlank() || !gateway.configured() || !gateway.available()) {
            return Optional.empty();
        }
        String userPrompt = UntrustedText.block("候选对象", describe(candidates))
                + UntrustedText.block("用户问题", truncate(query))
                + "\n只回复一个候选行原样内容，或 " + UNKNOWN;
        try {
            String answer = QuickModelCall.content(gateway, timeoutSeconds, "目标解析",
                    client -> client.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().content());
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
                String prefix = Texts.orEmpty(route.businessPrefix());
                if (!prefix.isBlank()) {
                    unique.putIfAbsent("ROUTE|" + prefix, ResourceTarget.route(prefix));
                }
                String service = Texts.orEmpty(route.serviceName());
                if (!service.isBlank()) {
                    unique.putIfAbsent("SERVICE|" + service, ResourceTarget.service(service));
                }
            }
        }
        if (instances != null) {
            for (InstanceSnapshot instance : instances) {
                String service = Texts.orEmpty(instance.serviceName());
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
        String host = Texts.orEmpty(instance.host());
        return host.isBlank() || instance.port() <= 0 ? "" : host + ":" + instance.port();
    }

    private static String truncate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= MAX_QUERY_LENGTH ? trimmed : trimmed.substring(0, MAX_QUERY_LENGTH);
    }

}