package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rover.admin.service.AdminConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminConfigReadAdapterTest {
    @Test void credentialValuesAndDefaultsNeverEnterModelEvidence() {
        var admin = mock(AdminConfigService.class);
        when(admin.listConfigs()).thenReturn(List.of(
                Map.of("component", "gateway", "key", "adminToken", "value", "write-secret", "defaultValue", "default-secret"),
                Map.of("component", "gateway", "key", "readTimeoutMillis", "value", "5000", "defaultValue", "3000")));
        var rows = new AdminConfigReadAdapter(admin).configs();
        assertEquals("[REDACTED]", rows.get(0).value()); assertEquals("[REDACTED]", rows.get(0).defaultValue());
        assertFalse(rows.toString().contains("write-secret")); assertEquals("5000", rows.get(1).value());
    }
}
