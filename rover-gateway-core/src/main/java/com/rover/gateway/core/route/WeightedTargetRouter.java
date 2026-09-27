package com.rover.gateway.core.route;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Author: Daylight
 * Created: 2026-09-27 14:20:00
 * Description: 灰度分流选版本：按权重把粘性键映射到路由的某一个 target
 *
 * <p>用固定刻度 + 连续区间（weighted slab），刻度必须与权重之和无关——否则调权重会把已分流的 key
 * 重新洗牌，放量不再单调，理由见 {@link #SCALE}。
 */
public final class WeightedTargetRouter {

    /** FNV-1a 64 位初始值与质数。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * 固定刻度：哈希先归一到 {@code [0, SCALE)} 的定长槽位，再按权重比例切区间。
     *
     * <p>刻度必须与「权重之和」无关。若直接拿总权重取模，改一个 target 的权重会让 total 变化，
     * 所有 key 的槽位一起漂移（5% 放到 20% 时原落 v2 的 key 会被打回 v1）。
     *
     * <p>取 1000 万：单条 target 权重上限 1 万（{@link RouteTarget#MAX_WEIGHT}），
     * 一条路由即使挂上千个 target 也远小于刻度，因此每个有权重的 target 至少能分到 1 格。
     */
    static final long SCALE = 10_000_000L;

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
        long total = 0;
        for (RouteTarget target : targets) {
            if (target != null && target.weight() > 0) {
                total += target.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        String key = stickyKey == null || stickyKey.isBlank() ? null : stickyKey;
        // 没有粘性键时按同一套区间做加权随机：随机槽位落进哪个区间就选哪个 target
        long slot = key == null
                ? ThreadLocalRandom.current().nextLong(SCALE)
                : Math.floorMod(hash(key + "|" + (routeKey == null ? "" : routeKey)), SCALE);
        long cursor = 0;
        long cumulative = 0;
        for (RouteTarget target : targets) {
            if (target == null || target.weight() <= 0) {
                continue;
            }
            cumulative += target.weight();
            // 上边界 = 累计权重占总量之比 × 刻度：权重只移动边界、不重排槽位，放量因此单调
            long upper = SCALE * cumulative / total;
            if (upper <= cursor) {
                // 兜底：仅当权重之和大于刻度（正常配置不会发生）时才会触发，保证每个 target 至少 1 格
                upper = cursor + 1;
            }
            if (slot < upper) {
                return target;
            }
            cursor = upper;
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
