package com.rover.agent.core.planning;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.ResourceTarget;
import java.util.List;

/**
 * 规划输入，包含用户问题、调查目标、已采集证据和判断边界。
 *
 * @param question    用户问题原文
 * @param target      本次请求的目标对象
 * @param path        取数用的请求路径（路由口径）；为空串表示没有可调查路径
 * @param evidence    已采集的证据
 * @param limitations 已产生的判断边界
 */
public record PlanningRequest(String question, ResourceTarget target, String path,
                              List<Evidence> evidence, List<String> limitations) {

    public PlanningRequest {
        question = question == null ? "" : question.trim();
        target = target == null ? ResourceTarget.unknown() : target;
        path = path == null ? "" : path.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}