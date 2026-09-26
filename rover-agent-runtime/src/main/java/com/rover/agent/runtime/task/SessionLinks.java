package com.rover.agent.runtime.task;

import com.rover.agent.core.model.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话与事件之间的引用维护：事件挂在会话的事件列表上，当前事件指针指向最近一次调查的事件。
 *
 * 这些是不可变会话记录的整体重建规则，登记表与保留策略都要用，因此单独放一处，
 * 避免两处各写一份"摘除事件时忘了把当前事件指针清掉"。
 */
final class SessionLinks {

    private SessionLinks() {
    }

    /** 把事件挂到会话上，并把当前事件指针指向它。 */
    static Session withIncident(Session session, String incidentId) {
        return new Session(session.sessionId(), session.userId(), session.title(), incidentId, session.status(),
                session.createdAtMillis(), System.currentTimeMillis(), append(session.incidentIds(), incidentId));
    }

    /** 从会话上摘除事件；被摘除的正好是当前事件时，当前事件指针回到空。 */
    static Session withoutIncident(Session session, String incidentId) {
        List<String> remaining = session.incidentIds().stream().filter(id -> !id.equals(incidentId)).toList();
        String active = incidentId.equals(session.activeIncidentId()) ? null : session.activeIncidentId();
        return new Session(session.sessionId(), session.userId(), session.title(), active, session.status(),
                session.createdAtMillis(), session.lastActiveAtMillis(), remaining);
    }

    static List<String> append(List<String> values, String item) {
        List<String> copy = new ArrayList<>(values);
        copy.add(item);
        return List.copyOf(copy);
    }
}