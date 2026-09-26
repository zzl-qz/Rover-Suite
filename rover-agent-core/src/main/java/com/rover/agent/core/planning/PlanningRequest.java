package com.rover.agent.core.planning;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.ResourceTarget;
import java.util.List;

/**
 * 规划输入：模型规划所需的全部只读上下文，一次给全，避免 Planner 自己到处取数。
 *
 * {@code evidence} 是已经采到的证据——第二轮起模型据此判断「还差什么」，
 * 这也是动态调查与固定链路的分水岭：下一步由当前事实决定，而不是由写死的边决定。
 *
 * @param question    用户问题原文
 * @param intent      意图判断
 * @param target      本次请求的目标对象
 * @param path        取数用的请求路径（路由口径）；为空串表示没有可调查路径
 * @param evidence    已采集的证据
 * @param limitations 已产生的判断边界
 */
public record PlanningRequest(String question, IntentDecision intent, ResourceTarget target, String path,
                              List<Evidence> evidence, List<String> limitations) {

    public PlanningRequest {
        question = question == null ? "" : question.trim();
        target = target == null ? ResourceTarget.unknown() : target;
        path = path == null ? "" : path.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}