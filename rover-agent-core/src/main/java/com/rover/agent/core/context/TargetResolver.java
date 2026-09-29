package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.port.InstanceReadPort;
import com.rover.agent.core.port.RouteReadPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.investigation.RouteMatcher;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.util.Texts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把自然语言问题解析成结构化调查对象（路由 / 服务 / 实例）。
 *
 * 解析优先级固定：
 * <ol>
 *   <li>调用方显式给出的 path / service / instance（高级上下文里的手工指定）；</li>
 *   <li>问题文本里能匹配到 Gateway 现有路由、注册中心现有服务或实例的部分；</li>
 *   <li>规则都匹配不上时，交给 {@link TargetInterpreter} 用已配置模型从候选中选一个；</li>
 *   <li>仍然确定不了就返回澄清，绝不猜测。</li>
 * </ol>
 *
 * 名称匹配以"是否为已有对象"为准：路由必须命中 Gateway 现有路由的业务前缀，服务名与实例地址
 * 必须出现在当前注册数据里。只有用户明确写了请求路径、而该路径没有对应路由时才退回澄清，
 * 避免对着一个不存在的对象做一轮"看似成功"的诊断。
 *
 * 本类不处理"追问沿用上一个目标"：那是会话连续性，不是解析。调用方（编排层）在拿到澄清结果、
 * 且 {@link AgentContext#currentTarget()} 已经是确定对象时，应沿用当前目标继续调查，
 * 只有确实没有可继承目标时才把澄清提问回给用户。
 */
public final class TargetResolver {

    /** 从问题文本里捞请求路径：以 / 开头，允许字母数字与常见路径字符。 */
    private static final Pattern PATH_TOKEN = Pattern.compile("/[A-Za-z0-9][A-Za-z0-9._~/-]*");

    /** 服务名判定用的最小长度：太短的片段容易撞上普通词。 */
    private static final int MIN_SERVICE_NAME_LENGTH = 3;

    private static final int MAX_QUERY_LENGTH = 1000;
    private static final int MAX_PATH_LENGTH = 512;

    /** 澄清时列出的候选对象条数上限：够用户挑就行，不是把整张路由表搬进对话框。 */
    private static final int MAX_HINT_OBJECTS = 5;

    private final RouteReadPort routes;
    private final InstanceReadPort instances;
    private final TargetInterpreter interpreter;

    public TargetResolver(RouteReadPort routes, InstanceReadPort instances, TargetInterpreter interpreter) {
        this.routes = routes;
        this.instances = instances;
        this.interpreter = interpreter == null ? TargetInterpreter.none() : interpreter;
    }

    /**
     * 解析一次提问的调查对象。
     *
     * @param query    用户问题原文
     * @param explicit 调用方显式指定的目标（来自高级上下文）；未指定时传 {@code null} 或
     *                 {@link ResourceTarget#unknown()}，此时按问题文本解析
     */
    public TargetResolution resolve(String query, ResourceTarget explicit) {
        if (isExplicit(explicit)) {
            return forTarget(explicit, readRoutes(), readInstances());
        }
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return TargetResolution.clarify("请描述要调查的路由、服务或实例。");
        }
        if (text.length() > MAX_QUERY_LENGTH) {
            text = text.substring(0, MAX_QUERY_LENGTH);
        }

        List<RouteSnapshot> routeList = readRoutes();
        List<InstanceSnapshot> instanceList = readInstances();

        ResourceTarget byPath = matchRoute(routeList, text);
        if (byPath != null) {
            return forTarget(byPath, routeList, instanceList);
        }
        ResourceTarget byService = matchService(routeList, instanceList, text);
        if (byService != null) {
            return forTarget(byService, routeList, instanceList);
        }
        ResourceTarget byInstance = matchInstance(instanceList, text);
        if (byInstance != null) {
            return forTarget(byInstance, routeList, instanceList);
        }

        Optional<ResourceTarget> inferred = interpreter.infer(text, routeList, instanceList);
        if (inferred.isPresent() && inferred.get().type() != TargetType.UNKNOWN
                && !inferred.get().value().isBlank()) {
            return forTarget(inferred.get(), routeList, instanceList);
        }
        return TargetResolution.clarify("没能在已注册的路由、服务或实例中找到调查对象，"
                + "请给出具体的请求路径、服务名或实例地址。" + availableHint(routeList, instanceList));
    }

    /**
     * 可调查对象的提示：把当前确实存在的路由前缀与服务名摆出来。
     *
     * 澄清时手里已经有这两份列表，只回一句「请给出对象」等于让用户对着空对话框猜；
     * 列出候选既省一轮往返，也不会猜错对象——列出的都是真实存在、可以直接调查的对象。
     * 没有任何可列举对象时返回空串，不编造。
     */
    private static String availableHint(List<RouteSnapshot> routeList, List<InstanceSnapshot> instanceList) {
        List<String> paths = (routeList == null ? List.<RouteSnapshot>of() : routeList).stream()
                .map(route -> Texts.orEmpty(route.businessPrefix()))
                .filter(prefix -> !prefix.isBlank())
                .distinct()
                .limit(MAX_HINT_OBJECTS)
                .toList();
        List<String> services = (instanceList == null ? List.<InstanceSnapshot>of() : instanceList).stream()
                .map(instance -> Texts.orEmpty(instance.serviceName()))
                .filter(name -> !name.isBlank())
                .distinct()
                .limit(MAX_HINT_OBJECTS)
                .toList();
        if (paths.isEmpty() && services.isEmpty()) {
            return "";
        }
        StringBuilder hint = new StringBuilder("\n当前可调查的对象：");
        if (!paths.isEmpty()) {
            hint.append("\n- 路由前缀：").append(String.join("、", paths));
        }
        if (!services.isEmpty()) {
            hint.append("\n- 服务名：").append(String.join("、", services));
        }
        return hint.toString();
    }

    /**
     * 把对象落到可调查的路由上：路由目标直接用它自己的取值，服务与实例目标要先找到一条现有路由。
     *
     * 路由目标不做"该路由是否存在"的检查，因为它已经是用户给出的取数口径本身——
     * 调查会照常执行并把"未匹配到路由"作为结论如实报出，这与既有链路的行为一致。
     */
    private static TargetResolution forTarget(ResourceTarget target, List<RouteSnapshot> routeList,
                                             List<InstanceSnapshot> instanceList) {
        if (target.type() == TargetType.ROUTE) {
            return TargetResolution.resolved(target, target.value());
        }
        String service = target.type() == TargetType.SERVICE
                ? target.value() : serviceOfInstance(instanceList, target.value());
        String path = service == null ? null : routePathOf(routeList, service);
        if (path == null) {
            // 这里只列路由：缺的是「该服务对应的请求路径」，列服务名帮不上忙。
            return TargetResolution.clarify("「" + target.value() + "」当前没有对应的 Gateway 路由，"
                    + "按现有调查口径无法取数；请给出该对象对应的请求路径。" + availableHint(routeList, null));
        }
        return TargetResolution.resolved(target, path);
    }

    private static String serviceOfInstance(List<InstanceSnapshot> instanceList, String address) {
        for (InstanceSnapshot instance : instanceList) {
            if (addressIgnoreCase(instance).equalsIgnoreCase(address) && !Texts.orEmpty(instance.serviceName()).isBlank()) {
                return Texts.orEmpty(instance.serviceName());
            }
        }
        return null;
    }

    private static String routePathOf(List<RouteSnapshot> routeList, String serviceName) {
        return routeList.stream()
                .filter(route -> Texts.orEmpty(route.serviceName()).equals(serviceName))
                .map(route -> Texts.orEmpty(route.businessPrefix()))
                .filter(prefix -> !prefix.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String addressIgnoreCase(InstanceSnapshot instance) {
        return Texts.orEmpty(instance.host()) + ":" + instance.port();
    }

    /** 显式目标必须带确定类型与取值，否则视同未指定。 */
    private static boolean isExplicit(ResourceTarget target) {
        return target != null && target.type() != TargetType.UNKNOWN && !target.value().isBlank();
    }

    /** 路径优先：取最长的、能命中现有路由的路径片段，并归一化为该路由的业务前缀。 */
    private static ResourceTarget matchRoute(List<RouteSnapshot> routeList, String text) {
        Matcher matcher = PATH_TOKEN.matcher(text);
        RouteSnapshot best = null;
        while (matcher.find()) {
            String token = trimTrailingSlash(matcher.group());
            if (token.length() > MAX_PATH_LENGTH) {
                continue;
            }
            RouteSnapshot matched = RouteMatcher.match(routeList, token);
            if (matched != null && (best == null
                    || Texts.orEmpty(matched.businessPrefix()).length() > Texts.orEmpty(best.businessPrefix()).length())) {
                best = matched;
            }
        }
        return best == null ? null : ResourceTarget.route(Texts.orEmpty(best.businessPrefix()));
    }

    /** 服务名：路由的目标服务名与注册实例的服务名一起作为已知集合，取最长的命中项。 */
    private static ResourceTarget matchService(List<RouteSnapshot> routeList, List<InstanceSnapshot> instanceList,
                                               String text) {
        Set<String> names = new LinkedHashSet<>();
        for (RouteSnapshot route : routeList) {
            if (!Texts.orEmpty(route.serviceName()).isBlank()) {
                names.add(Texts.orEmpty(route.serviceName()));
            }
        }
        for (InstanceSnapshot instance : instanceList) {
            if (!Texts.orEmpty(instance.serviceName()).isBlank()) {
                names.add(Texts.orEmpty(instance.serviceName()));
            }
        }
        return names.stream()
                .filter(name -> name.length() >= MIN_SERVICE_NAME_LENGTH)
                .filter(name -> containsIgnoreCase(text, name))
                .max(Comparator.comparingInt(String::length))
                .map(ResourceTarget::service)
                .orElse(null);
    }

    /** 实例地址：必须是当前注册数据里存在的 {@code host:port}，不做任何补全或猜测。 */
    private static ResourceTarget matchInstance(List<InstanceSnapshot> instanceList, String text) {
        List<String> addresses = new ArrayList<>();
        for (InstanceSnapshot instance : instanceList) {
            if (!Texts.orEmpty(instance.host()).isBlank() && instance.port() > 0) {
                addresses.add(instance.host().trim() + ":" + instance.port());
            }
        }
        return addresses.stream()
                .filter(address -> containsIgnoreCase(text, address))
                .max(Comparator.comparingInt(String::length))
                .map(ResourceTarget::instance)
                .orElse(null);
    }

    private List<RouteSnapshot> readRoutes() {
        try {
            List<RouteSnapshot> snapshot = routes.routes();
            return snapshot == null ? List.of() : snapshot;
        } catch (SnapshotUnavailableException ex) {
            return List.of();
        }
    }

    private List<InstanceSnapshot> readInstances() {
        try {
            List<InstanceSnapshot> snapshot = instances.instances();
            return snapshot == null ? List.of() : snapshot;
        } catch (SnapshotUnavailableException ex) {
            return List.of();
        }
    }

    private static String trimTrailingSlash(String token) {
        String trimmed = token;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean containsIgnoreCase(String text, String candidate) {
        return text.toLowerCase().contains(candidate.toLowerCase());
    }

}