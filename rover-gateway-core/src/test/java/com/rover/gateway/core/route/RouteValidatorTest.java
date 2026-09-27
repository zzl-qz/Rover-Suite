package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.gateway.core.discovery.DiscoveryType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Author: Daylight
 * Created: 2026-09-27 15:20:00
 * Description: 动态路由校验器契约测试：同服务约束、权重和、静态/动态互斥与副本语义
 */
class RouteValidatorTest {

    /** 动态模式校验器：targets 形态的路由只在走服务发现时才合法。 */
    private final RouteValidator validator = new RouteValidator(DiscoveryType.NAMESERVER);

    @Test
    void targetsMustShareSameServiceName() {
        RouteConfig route = versionedRoute("/api/user",
                new RouteTarget("demo", "v1", 50), new RouteTarget("other", "v2", 50));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(route)));
        assertTrue(ex.getMessage().contains("同一个 serviceName"), "报错应指出 serviceName 不一致，实际=" + ex.getMessage());
    }

    @Test
    void totalWeightMustBePositive() {
        RouteConfig route = versionedRoute("/api/user",
                new RouteTarget("demo", "v1", 0), new RouteTarget("demo", "v2", 0));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(route)));
        assertTrue(ex.getMessage().contains("权重之和必须大于 0"), "报错应指出权重和无版本可接流，实际=" + ex.getMessage());
    }

    @Test
    void staticUpstreamAndTargetsAreMutuallyExclusive() {
        RouteConfig route = versionedRoute("/api/user", new RouteTarget("demo", "v1", 100));
        route.setTargetUrl("http://127.0.0.1:8080");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(route)));
        assertTrue(ex.getMessage().contains("不能同时写"), "报错应指出静态上游与 targets 互斥，实际=" + ex.getMessage());
    }

    @Test
    void duplicateTargetIsRejected() {
        RouteConfig route = versionedRoute("/api/user",
                new RouteTarget("demo", "v1", 50), new RouteTarget("demo", "v1", 50));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(route)));
        assertTrue(ex.getMessage().contains("重复目标"), "报错应指出重复目标，实际=" + ex.getMessage());
    }

    @Test
    void weightOutOfRangeIsRejected() {
        RouteConfig negative = versionedRoute("/api/a",
                new RouteTarget("demo", "v1", -1), new RouteTarget("demo", "v2", 100));
        assertThrows(IllegalArgumentException.class, () -> validator.normalizeAndValidate(List.of(negative)),
                "负权重必须被拒绝");

        RouteConfig tooLarge = versionedRoute("/api/b",
                new RouteTarget("demo", "v1", RouteTarget.MAX_WEIGHT + 1), new RouteTarget("demo", "v2", 0));
        assertThrows(IllegalArgumentException.class, () -> validator.normalizeAndValidate(List.of(tooLarge)),
                "超过 MAX_WEIGHT 的权重必须被拒绝");
    }

    @Test
    void stickyHeaderMustBeValidHttpHeaderName() {
        RouteConfig illegal = versionedRoute("/api/bad", new RouteTarget("demo", "v1", 100));
        illegal.setStickyHeader("bad header!");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(illegal)));
        assertTrue(ex.getMessage().contains("stickyHeader"), "报错应指出粘性头名非法，实际=" + ex.getMessage());

        RouteConfig legal = versionedRoute("/api/ok", new RouteTarget("demo", "v1", 100));
        legal.setStickyHeader("X-User-Id");
        assertEquals(1, validator.normalizeAndValidate(List.of(legal)).size(), "合法粘性头名应通过校验");
    }

    @Test
    void duplicateBusinessPrefixIsRejected() {
        RouteConfig first = versionedRoute("/api/user", new RouteTarget("demo", "v1", 100));
        RouteConfig second = versionedRoute("/api/user", new RouteTarget("demo", "v2", 100));
        second.setId("another");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.normalizeAndValidate(List.of(first, second)));
        assertTrue(ex.getMessage().contains("businessPrefix 重复"), "报错应指出前缀重复，实际=" + ex.getMessage());
    }

    @Test
    void normalizeAndValidateReturnsDetachedCopies() {
        RouteConfig original = versionedRoute("/api/user",
                new RouteTarget("demo", "v1", 90), new RouteTarget("demo", "v2", 10));
        original.setId("r-1");

        List<RouteConfig> normalized = validator.normalizeAndValidate(List.of(original));
        RouteConfig copy = normalized.get(0);

        // 改副本不能影响入参对象：这才是「校验失败不改内存」的根基
        copy.setBusinessPrefix("/api/changed");
        copy.getTargets().add(new RouteTarget("demo", "v3", 1));

        assertEquals("/api/user", original.getBusinessPrefix(), "改返回值不应改到入参路由的前缀");
        assertEquals(2, original.getTargets().size(), "改返回值的目标列表不应改到入参路由的 targets");
        assertEquals(90, original.getTargets().get(0).weight(), "改返回值不应改到入参路由的权重");
    }

    /** 构造一条动态路由，targets 用可变列表以便后续改动。 */
    private static RouteConfig versionedRoute(String prefix, RouteTarget... targets) {
        RouteConfig route = new RouteConfig();
        route.setBusinessPrefix(prefix);
        route.setTargets(new ArrayList<>(List.of(targets)));
        return route;
    }
}
