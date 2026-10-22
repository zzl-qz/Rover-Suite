package com.rover.agent.core.repository;

import com.rover.agent.core.model.ActionEffectVerification;
import java.util.List;
import java.util.Optional;

/** Durable observations of business effect, separate from the control-plane action state. */
public interface ActionEffectVerificationRepository {
    Optional<ActionEffectVerification> find(String actionId, ActionEffectVerification.Phase phase);
    ActionEffectVerification save(ActionEffectVerification verification);
    List<ActionEffectVerification> due(long nowMillis, int limit);
}
