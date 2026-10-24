package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.RouteControlException;
import com.rover.agent.core.port.RouteOperationStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminRouteControlAdapterTest {
    @Test
    void serverErrorRequiresReconciliationInsteadOfClaimingNotApplied() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.adjustTargetWeight(anyMap())).thenThrow(new ManageApiCallException(500, "commit outcome unknown"));
        AdminRouteControlAdapter adapter = new AdminRouteControlAdapter(admin);
        RouteControlException error = assertThrows(RouteControlException.class,
                () -> adapter.adjustTargetWeight("orders", "demo", "v2", 0, 5, "original"));
        assertTrue(error.outcomeUnknown());
        verify(admin, times(1)).adjustTargetWeight(anyMap());
    }

    @Test
    void expiredReceiptRemainsExpiredAtAgentBoundary() {
        AdminConfigService admin = mock(AdminConfigService.class);
        when(admin.routeOperation("old")).thenReturn(Map.of("status", "EXPIRED", "revision", 3, "currentRevision", 7));
        var receipt = new AdminRouteControlAdapter(admin).operation("old");
        assertEquals(RouteOperationStatus.EXPIRED, receipt.status());
        assertFalse(receipt.applied());
    }
}
