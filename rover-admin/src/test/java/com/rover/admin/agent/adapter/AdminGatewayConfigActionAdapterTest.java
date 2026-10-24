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
                .thenReturn(Map.of("operationId", "agent-op-unknown", "status", "UNKNOWN", "notFound", true));

        var result = new AdminGatewayConfigActionAdapter(admin).query("agent-op-unknown", CONFIG_KEY, "0", "1");

        assertEquals(GatewayConfigActionReceipt.Status.UNKNOWN, result.status());
        verify(admin).gatewayConfigAction("agent-op-unknown");
        verify(admin, never()).executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void explicitNotFoundMarkerMustStillMatchOperationAndUnknownStatus() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("missing-op"))
                .thenReturn(Map.of("operationId", "other-op", "status", "UNKNOWN", "notFound", true));
        var adapter = new AdminGatewayConfigActionAdapter(admin);
        GatewayConfigActionException wrongOperation = assertThrows(GatewayConfigActionException.class,
                () -> adapter.query("missing-op", CONFIG_KEY, "0", "1"));
        assertTrue(wrongOperation.outcomeUnknown());

        when(admin.gatewayConfigAction("wrong-status-op"))
                .thenReturn(Map.of("operationId", "wrong-status-op", "status", "APPLIED", "notFound", true));
        GatewayConfigActionException wrongStatus = assertThrows(GatewayConfigActionException.class,
                () -> adapter.query("wrong-status-op", CONFIG_KEY, "0", "1"));
        assertTrue(wrongStatus.outcomeUnknown());
    }

    @Test
    void queryRejectsIncompleteUnknownReceiptInsteadOfInventingMissingIntent() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("agent-op-unknown"))
                .thenReturn(Map.of("operationId", "agent-op-unknown", "status", "UNKNOWN", "receiptFound", true));
        var exception = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).query("agent-op-unknown", CONFIG_KEY, "0.0", "1"));
        assertTrue(exception.outcomeUnknown());
        verify(admin, never()).executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void receiptFoundFalseDoesNotMakeIncompleteUnknownReceiptSafe() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("ambiguous-op"))
                .thenReturn(Map.of("operationId", "ambiguous-op", "status", "UNKNOWN", "receiptFound", false));
        assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).query("ambiguous-op", CONFIG_KEY, "0", "1"));
    }

    @Test
    void queryRejectsPartialUnknownIntent() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("partial-op"))
                .thenReturn(Map.of("operationId", "partial-op", "status", "UNKNOWN", "key", CONFIG_KEY));
        var error = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).query("partial-op", CONFIG_KEY, "0", "1"));
        assertTrue(error.outcomeUnknown());
    }

    @Test
    void executeRejectsIncompleteAppliedReceipt() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("operationId", "op", "status", "APPLIED"));
        var error = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).execute("op", CONFIG_KEY, "0", "1"));
        assertTrue(error.outcomeUnknown());
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

    @Test
    void queryRejectsReceiptForAnotherIntent() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.gatewayConfigAction("same-op"))
                .thenReturn(receipt("same-op", "APPLIED", "1", "0"));
        GatewayConfigActionException error = assertThrows(GatewayConfigActionException.class,
                () -> new AdminGatewayConfigActionAdapter(admin).query("same-op", CONFIG_KEY, "0", "1"));
        assertTrue(error.outcomeUnknown());
        verify(admin, never()).executeGatewayConfigAction(anyString(), anyString(), anyString(), anyString());
    }

    private static Map<String, Object> receipt(String operationId, String status,
                                               String expected, String desired) {
        return Map.of("operationId", operationId, "key", CONFIG_KEY, "status", status,
                "expectedValue", expected, "desiredValue", desired, "replayed", false,
                "message", "receipt");
    }
}
