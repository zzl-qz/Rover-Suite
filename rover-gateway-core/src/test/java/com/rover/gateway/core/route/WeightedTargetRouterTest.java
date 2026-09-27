package com.rover.gateway.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Author: Daylight
 * Created: 2026-09-27 15:10:00
 * Description: 灰度分流器契约测试：比例精度、放量单调、0 权重不接流与粘性稳定性
 *
 * 样本足够大即可，不依赖固定随机种子——连续区间算法是纯函数，大样本下比例收敛是确定性的。
 */
class WeightedTargetRouterTest {

    /** 统计样本量：10 万个键足以把 5% 档的采样误差压到 0.1 个百分点以内。 */
    private static final int SAMPLE = 100_000;

    /** 分流用的路由盐，同一批 key 在不同用例里保持一致。 */
    private static final String ROUTE_KEY = "route";

    @Test
    void weightedRatioMatchesConfiguredWeight() {
        List<RouteTarget> narrow = List.of(new RouteTarget("demo", "v1", 95), new RouteTarget("demo", "v2", 5));
        double ratio95 = v2Ratio(narrow);
        // 5% ± 0.5 个百分点：既排除「比例算错」，也留出哈希微小偏斜的余量
        assertTrue(ratio95 > 0.045 && ratio95 < 0.055,
                "v1:95/v2:5 时 v2 实际占比=" + ratio95 + "，期望约 5%（样本 " + SAMPLE + "）");

        List<RouteTarget> wide = List.of(new RouteTarget("demo", "v1", 80), new RouteTarget("demo", "v2", 20));
        double ratio20 = v2Ratio(wide);
        assertTrue(ratio20 > 0.19 && ratio20 < 0.21,
                "v1:80/v2:20 时 v2 实际占比=" + ratio20 + "，期望约 20%（样本 " + SAMPLE + "）");
    }

    @Test
    void increasingWeightNeverRemovesPreviouslySelectedKeys() {
        List<RouteTarget> narrow = List.of(new RouteTarget("demo", "v1", 95), new RouteTarget("demo", "v2", 5));
        List<RouteTarget> wide = List.of(new RouteTarget("demo", "v1", 80), new RouteTarget("demo", "v2", 20));

        Set<String> v2KeysBefore = v2Keys(narrow);
        assertTrue(!v2KeysBefore.isEmpty(), "5% 档应当有键命中 v2，否则样本或哈希有问题");

        // 放量单调：5% 时落 v2 的键，在 20% 时必须一个都不少地继续落 v2
        for (String key : v2KeysBefore) {
            RouteTarget after = WeightedTargetRouter.select(wide, key, ROUTE_KEY);
            assertNotNull(after, "键 " + key + " 放量后不应落空");
            assertEquals("v2", after.group(),
                    "键 " + key + " 原本落 v2，把 v1 权重从 95 降到 80 后被打回了 " + after.group());
        }
    }

    /**
     * 只提高一个 target 的权重（总权重随之变化）时，该 target 原持有的键必须一个不丢。
     *
     * 这是「按总权重取模」实现最容易翻车的场景：100 → 115 会让所有键的槽位一起漂移，
     * 原本落 v2 的键被打回 v1，灰度放量反而把已放量的用户抽走。
     */
    @Test
    void increasingOneGroupWeightKeepsItsOwnKeys() {
        List<RouteTarget> narrow = List.of(new RouteTarget("demo", "v1", 95), new RouteTarget("demo", "v2", 5));
        List<RouteTarget> widened = List.of(new RouteTarget("demo", "v1", 95), new RouteTarget("demo", "v2", 20));

        Set<String> v2KeysBefore = v2Keys(narrow);
        assertTrue(!v2KeysBefore.isEmpty(), "5% 档应当有键命中 v2，否则样本或哈希有问题");

        for (String key : v2KeysBefore) {
            RouteTarget after = WeightedTargetRouter.select(widened, key, ROUTE_KEY);
            assertNotNull(after, "键 " + key + " 放量后不应落空");
            assertEquals("v2", after.group(),
                    "键 " + key + " 原本落 v2，只提高 v2 权重（v1 不变）后不应被打回 " + after.group());
        }

        double ratio = v2Ratio(widened);
        // 95:20 的归一化占比是 20/115≈17.4%，不是 20/100
        assertTrue(ratio > 0.165 && ratio < 0.183,
                "v1:95/v2:20 时 v2 实际占比=" + ratio + "，期望约 20/115≈17.4%（样本 " + SAMPLE + "）");
    }

    @Test
    void zeroWeightTargetReceivesNoTraffic() {
        List<RouteTarget> targets = List.of(new RouteTarget("demo", "v1", 100), new RouteTarget("demo", "v2", 0));
        for (int i = 0; i < 10_000; i++) {
            RouteTarget selected = WeightedTargetRouter.select(targets, "user-" + i, ROUTE_KEY);
            assertNotNull(selected, "v1 有正权重，不应返回 null");
            assertEquals("v1", selected.group(), "v2 权重为 0 时不应命中 v2，key=user-" + i);
        }
    }

    @Test
    void allZeroWeightReturnsNull() {
        List<RouteTarget> targets = List.of(new RouteTarget("demo", "v1", 0), new RouteTarget("demo", "v2", 0));
        assertNull(WeightedTargetRouter.select(targets, "user-1", ROUTE_KEY),
                "所有 target 权重为 0 时应返回 null 表示无可接流目标");
    }

    @Test
    void sameStickyKeyAlwaysSelectsSameTarget() {
        List<RouteTarget> targets = List.of(new RouteTarget("demo", "v1", 50), new RouteTarget("demo", "v2", 50));
        RouteTarget first = WeightedTargetRouter.select(targets, "user-42", ROUTE_KEY);
        assertNotNull(first, "有权重的 target 不应返回 null");
        for (int i = 0; i < 1000; i++) {
            assertSame(first, WeightedTargetRouter.select(targets, "user-42", ROUTE_KEY),
                    "同一粘性键必须恒定命中同一个 target 实例，否则灰度用户会漂移");
        }
    }

    @Test
    void blankStickyKeyFallsBackToRandomAndCoversAllTargets() {
        List<RouteTarget> targets = List.of(new RouteTarget("demo", "v1", 100), new RouteTarget("demo", "v2", 100));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            RouteTarget selected = WeightedTargetRouter.select(targets, "   ", ROUTE_KEY);
            assertNotNull(selected, "粘性键为空时应按权重随机兜底，不应返回 null");
            seen.add(selected.group());
        }
        assertEquals(Set.of("v1", "v2"), seen, "权重相同时随机兜底也应覆盖到两个 target，实际=" + seen);
    }

    @Test
    void routeKeySaltsTheSlot() {
        List<RouteTarget> targets = List.of(new RouteTarget("demo", "v1", 50), new RouteTarget("demo", "v2", 50));
        int differ = 0;
        for (int i = 0; i < 10_000; i++) {
            RouteTarget a = WeightedTargetRouter.select(targets, "user-" + i, "A");
            RouteTarget b = WeightedTargetRouter.select(targets, "user-" + i, "B");
            if (!a.group().equals(b.group())) {
                differ++;
            }
        }
        // 只断言「盐确实改变了落点分布」，不写死具体方向，避免依赖哈希细节
        assertTrue(differ > 1000,
                "不同 routeKey 应当把落点打散，实际落点不同的键只有 " + differ + " / 10000");
    }

    /** 统计同一批键里落 v2 的比例。 */
    private static double v2Ratio(List<RouteTarget> targets) {
        return (double) v2Keys(targets).size() / SAMPLE;
    }

    /** 收集同一批键里落在 v2 的键集合。 */
    private static Set<String> v2Keys(List<RouteTarget> targets) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < SAMPLE; i++) {
            String key = "user-" + i;
            RouteTarget selected = WeightedTargetRouter.select(targets, key, ROUTE_KEY);
            if (selected != null && "v2".equals(selected.group())) {
                keys.add(key);
            }
        }
        return keys;
    }
}
