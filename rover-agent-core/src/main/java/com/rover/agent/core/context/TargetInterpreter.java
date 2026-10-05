package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;
import java.util.Optional;

/** 规则无法匹配时，辅助从已有候选中选择调查对象；不确定时返回空。 */
@FunctionalInterface
public interface TargetInterpreter {

    /**
     * 从自然语言与已知候选中推断调查对象。
     *
     * @param query     用户问题原文
     * @param routes    当前已知路由快照；不可用时为空列表
     * @param instances 当前已知实例快照；不可用时为空列表
     * @return 推断出的对象；没有把握时返回空
     */
    Optional<ResourceTarget> infer(String query, List<RouteSnapshot> routes, List<InstanceSnapshot> instances);

    /** 不做推断的默认实现：规则解析失败即请求澄清。 */
    static TargetInterpreter none() {
        return (query, routes, instances) -> Optional.empty();
    }
}