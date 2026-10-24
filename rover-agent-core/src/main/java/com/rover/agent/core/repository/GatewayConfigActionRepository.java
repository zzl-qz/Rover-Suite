package com.rover.agent.core.repository;

import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Persistence contract for the separately typed Gateway config action aggregate. */
public interface GatewayConfigActionRepository {
    GatewayConfigAction save(GatewayConfigAction action);
    Optional<GatewayConfigAction> find(String actionId);
    Optional<GatewayConfigAction> findCompensation(String actionId);
    List<GatewayConfigAction> bySession(String sessionId);
    boolean hasNonTerminalForSession(String sessionId);
    List<GatewayConfigAction> byStatus(java.util.Set<ConfigActionStatus> statuses, int limit);
    Optional<GatewayConfigAction> transition(String actionId, ConfigActionStatus expected,
                                              UnaryOperator<GatewayConfigAction> mutation);
}
