package com.rover.agent.core.journal;

/**
 * 某个路由或服务上仍有效的一条运维笔记。由已确认的调查派生，不是用户偏好。
 */
public record ResourceNote(String resourceKey, String symptom, String rootCause, String usefulSteps,
                           String pitfalls, String sourceSessionId, String sourceTaskId, long updatedAtMillis,
                           ReviewStatus reviewStatus, String correction, String reviewedBy, long reviewedAtMillis,
                           long version) {
    public enum ReviewStatus { AUTOMATIC, CORRECTED, RETRACTED }

    public ResourceNote(String resourceKey, String symptom, String rootCause, String usefulSteps, String pitfalls,
                        String sourceSessionId, String sourceTaskId, long updatedAtMillis) {
        this(resourceKey, symptom, rootCause, usefulSteps, pitfalls, sourceSessionId, sourceTaskId, updatedAtMillis,
                ReviewStatus.AUTOMATIC, null, null, 0, 0);
    }
}
