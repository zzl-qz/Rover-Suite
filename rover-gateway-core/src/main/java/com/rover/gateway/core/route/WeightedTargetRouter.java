package com.rover.gateway.core.route;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-09-27 14:20:00
 * Description: 灰度分流选版本：按权重把粘性键映射到路由的某一个 target
 *
 * <p><b>为什么不用「总权重取模分桶」</b>：把 key 空间切成等宽桶、再按权重分配桶，
 * 在权重变化时会把几乎所有用户重新洗一遍——5% 放到 20% 时，原本落 v2 的用户会有一部分被打回 v1，
 * 灰度实验的用户连续性就断了。
 *
 * <p><b>本实现用「连续区间（weighted slab）」</b>：按 {@code targets} 的声明顺序，
 * 把 key 空间切成与权重成正比的区间，{@code slot = hash(key) mod 总权重} 落在哪个区间就选哪个 target。
 * 于是「把权重从 v1 挪给紧邻其后的 v2」只会让 v2 的左边界向左扩张，
 * v2 原本持有的 key（右段）一个都不会交还——放量单调，正是 canary 需要的性质。
 *
 * <p><b>为什么不用一致性哈希环</b>：环上放 {@code weight} 个虚拟节点时，节点总数就是总权重
 * （典型量级只有 100），比例误差受节点数限制，无法精确表达 5% 这类小比例；
 * 而连续区间是精确比例，且是纯函数：O(N) 一次遍历，不用预计算环、不用跨层传递持有者对象。
 * 代价是失去「成员变更只重映射 1/N」的性质——本阶段不需要它，因为放量场景下权重是渐进调整的。
 *
 * <p>哈希用 FNV-1a 64 位：{@code String.hashCode()} 只差一个字符就会聚集（"k#0" 与 "k#1" 只差 1），
 * 拿它做分流会破坏比例。
 */
public final class WeightedTargetRouter {

    /** FNV-1a 64 位初始值与质数。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private WeightedTargetRouter() {
    }

    /**
     * 按粘性键选出一个 target。
     *
     * @param route     动态路由；targets 为空或权重全为 0 时返回 null
     * @param stickyKey 粘性键；为空表示无法定位用户，退化按权重随机
     * @return 命中的 target；无可接流 target 时 null
     */
    public static RouteTarget select(RouteConfig route, String stickyKey) {
        if (route == null) {
            return null;
        }
        return select(route.getTargets(), stickyKey, routeKeyOf(route));
    }

    /**
     * 按粘性键选出一个 target。
     *
     * @param targets   target 列表，顺序即区间顺序
     * @param stickyKey 粘性键，为空时按权重随机
     * @param routeKey  参与哈希的路由标识，用来把不同路由的落点打散
     * @return 命中的 target；无可接流 target 时 null
     */
    public static RouteTarget select(List<RouteTarget> targets, String stickyKey, String routeKey) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        int total = 0;
        for (RouteTarget target : targets) {
            if (target != null && target.weight() > 0) {
                total += target.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        String key = stickyKey == null || stickyKey.isBlank() ? null : stickyKey;
        long slot = key == null
                ? ThreadLocalRandom.current().nextInt(total)
                : Math.floorMod(hash(key + "|" + (routeKey == null ? "" : routeKey)), total);
        int cursor = 0;
        for (RouteTarget target : targets) {
            if (target == null || target.weight() <= 0) {
                continue;
            }
            cursor += target.weight();
            if (slot < cursor) {
                return target;
            }
        }
        return null;
    }

    /** 路由标识：id 优先，没有就退到业务前缀，保证同一条路由的哈希盐稳定。 */
    private static String routeKeyOf(RouteConfig route) {
        if (route.getId() != null && !route.getId().isBlank()) {
            return route.getId();
        }
        return route.getBusinessPrefix();
    }

    /** FNV-1a 64 位哈希，逐字符异或再乘质数。 */
    static long hash(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
