package com.rover.admin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.agent.core.port.AgentAccess.Permission;
import org.junit.jupiter.api.Test;

class AgentSecurityFiltersTest {
    @Test
    void routeIsolationApiAllowsReadsButRestrictsWritesToSystemManagers() {
        assertEquals(Permission.READ, AgentSecurityFilters.permission("/api/routes/instances/isolations", "GET"));
        assertEquals(Permission.MANAGE_SYSTEM, AgentSecurityFilters.permission("/api/routes/instances/isolations", "POST"));
        assertEquals(Permission.MANAGE_SYSTEM, AgentSecurityFilters.permission("/api/agent/routes/instances/isolations", "GET"));
        assertEquals(Permission.MANAGE_SYSTEM, AgentSecurityFilters.permission("/api/agent/routes/instances/isolations", "POST"));
    }

    @Test
    void gatewayConfigProposalAndApprovalHaveSeparatePermissions() {
        assertEquals(Permission.PROPOSE, AgentSecurityFilters.permission("/api/agent/config-actions", "POST"));
        assertEquals(Permission.PROPOSE, AgentSecurityFilters.permission(
                "/api/agent/config-actions/sessions/s1/config-actions", "POST"));
        assertEquals(Permission.APPROVE, AgentSecurityFilters.permission(
                "/api/agent/config-actions/a1/approve", "POST"));
        assertEquals(Permission.APPROVE, AgentSecurityFilters.permission(
                "/api/agent/config-actions/a1/reconcile", "POST"));
        assertEquals(Permission.READ, AgentSecurityFilters.permission(
                "/api/agent/config-actions/sessions/s1", "GET"));
    }
}
