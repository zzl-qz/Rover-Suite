package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.GatewayConfigActionException;
import com.rover.agent.core.port.GatewayConfigActionReceipt;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminGatewayConfigActionAdapterTest {
    private static final String CONFIG_KEY = "gateway.trace.sampleRate";

    @Test
    void executeUsesPersistedOperationIdAndChecksReturnedIntent() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.executeGatewayConfigAction("agent-op-1", CONFIG_KEY, "0", "1"))
                .thenReturn(receipt("agent-op-1", "APPLIED", "0", "1"));

        var result = new AdminGatewayConfigActionAdapter(admin).execute("agent-op-1", CONFIG_KEY, "0", "1");

        assertEquals(GatewayConfigActionReceipt.Status.APPLIED, result.status());
        assertEquals("agent-op-1", result.operationId());
        verify(admin).executeGatewayConfigAction("agent-op-1", CONFIG_KEY, "0", "1");
        verifyNoMoreInteractions(admin);
    }

    @Test
    void queryReturnsUnknownWithoutResubmittingWrite() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("agent-op-unknown"))
                .thenReturn(Map.of("operationId", "agent-op-unknown", "status", "UNKNOWN"));

        var result = new AdminGatewayConfigActionAdapter(admin).query("agent-op-unknown");

        assertEquals(GatewayConfigActionReceipt.Status.UNKNOWN, result.status());
        verify(admin).gatewayConfigAction("agent-op-unknown");
        verify(admin, never()).executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void timeoutAndServerFailureAreUnknownButDefinitiveClientRejectionIsNot() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ManageApiCallException(0, "timeout"));
        var adapter = new AdminGatewayConfigActionAdapter(admin);
        GatewayConfigActionException timeout = assertThrows(GatewayConfigActionException.class,
                () -> adapter.execute("timeout-op", CONFIG_KEY, "0", "1"));
        assertTrue(timeout.outcomeUnknown());

        reset(admin);
        when(admin.executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ManageApiCallException(409, "stale intent"));
        GatewayConfigActionException rejected = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).execute("conflict-op", CONFIG_KEY, "0", "1"));
        assertFalse(rejected.outcomeUnknown());
    }

    @Test
    void mismatchedReceiptCannotBeAcceptedAsSuccess() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(receipt("different-op", "APPLIED", "0", "1"));

        GatewayConfigActionException error = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).execute("expected-op", CONFIG_KEY, "0", "1"));

        assertTrue(error.outcomeUnknown());
    }

    private static Map<String, Object> receipt(String operationId, String status,
                                               String expected, String desired) {
        return Map.of("operationId", operationId, "key", CONFIG_KEY, "status", status,
                "expectedValue", expected, "desiredValue", desired, "replayed", false,
                "message", "receipt");
    }
}
