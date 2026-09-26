package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;

/**
 * 目标解析结果：要么得到一个确定的调查对象（并给出可调查的路由路径），要么明确要求用户澄清。
 *
 * 「确定的对象」必须同时给出 {@code investigationPath}：当前调查链是按路由口径取数的
 * （路由匹配 → 实例 → 指标 → 追踪），服务与实例目标都要先落到一条现有路由上才有事实可采。
 * 落到路由上之前不会开始调查，因此解析不出路径时返回的就是澄清，而不是一轮空转的诊断。
 *
 * @param target            解析出的调查对象；需要澄清时为 {@link ResourceTarget#unknown()}
 * @param investigationPath 可调查的请求路径（路由口径）；需要澄清时为空串
 * @param clarification     澄清提问；已解析出对象时为 {@code null}
 */
public record TargetResolution(ResourceTarget target, String investigationPath, String clarification) {

    public TargetResolution {
        target = target == null ? ResourceTarget.unknown() : target;
        investigationPath = investigationPath == null ? "" : investigationPath.trim();
    }

    /** 已确定调查对象，并给出按路由口径取数的路径。 */
    public static TargetResolution resolved(ResourceTarget target, String investigationPath) {
        return new TargetResolution(target, investigationPath, null);
    }

    /** 无法确定对象，必须向用户澄清。 */
    public static TargetResolution clarify(String question) {
        return new TargetResolution(ResourceTarget.unknown(), "", question);
    }

    /** 是否需要向用户澄清。 */
    public boolean needsClarification() {
        return clarification != null && !clarification.isBlank();
    }
}