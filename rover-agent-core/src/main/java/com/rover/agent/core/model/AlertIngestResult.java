package com.rover.agent.core.model;

/** Stable webhook result; duplicate firing deliveries point back to the original task. */
public record AlertIngestResult(String sessionId, String incidentId, String taskId, TaskStatus taskStatus,
                                boolean duplicate, boolean resolved, long generation) {
    public AlertIngestResult {
        if (generation < 0 || !resolved && taskId == null) {
            throw new IllegalArgumentException("alert ingest result is inconsistent");
        }
    }
}
