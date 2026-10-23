package com.rover.agent.core.repository;

import com.rover.agent.core.model.ActionCertification;
import com.rover.agent.core.model.ActionCertificationSubject;
import com.rover.agent.core.model.ActionShadowRun;
import com.rover.agent.core.model.ResourceScope;
import java.util.List;
import java.util.Optional;

/** Durable shadow evidence and versioned operator certifications. */
public interface ActionCertificationRepository {
    ActionShadowRun saveShadow(ActionShadowRun run);
    List<ActionShadowRun> recentShadowRuns(String fingerprint, int limit);
    List<ActionShadowRun> recentShadowRuns(ResourceScope scope, int limit);
    Optional<ActionCertification> current(String fingerprint);
    Optional<ActionCertification> atVersion(String fingerprint, long version);
    default Optional<ActionCertification> current(ActionCertificationSubject subject, long nowMillis) {
        return current(subject.fingerprint()).filter(cert -> cert.current(subject, nowMillis));
    }
    List<ActionCertification> current(ResourceScope scope);
    ActionCertification save(ActionCertification certification, long expectedVersion);
}
