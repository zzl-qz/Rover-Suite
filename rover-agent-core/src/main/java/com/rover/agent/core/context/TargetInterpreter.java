package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * 目标推断端口：当规则匹配不出调查对象时，用已配置的模型做一次结构化辅助。
 *
 * 实现只能从传入的候选中挑选，不允许凭空构造不存在的对象——把自然语言映射成"目标"这件事，
 * 错判的代价高于不判：宁可让用户澄清，也不能对着错误的服务做诊断。
 */
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