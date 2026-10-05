package com.rover.agent.core.context;

import com.rover.agent.core.model.ResourceTarget;

/**
 * 调查目标解析结果；确定目标须包含 investigationPath，否则要求澄清。
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