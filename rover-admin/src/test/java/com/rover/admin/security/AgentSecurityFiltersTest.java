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
}
