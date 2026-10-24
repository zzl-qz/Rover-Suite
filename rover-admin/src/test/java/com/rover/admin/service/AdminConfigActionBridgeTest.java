package com.rover.admin.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.config.AdminProperties;
import com.rover.common.constants.ManageApiPaths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminConfigActionBridgeTest {
    private static final String GATEWAY = "http://gateway.test";

    @Test
    void submitPassesExactOperationAndCasIntentToGateway() throws Exception {
        ManageHttpClient http = mock(ManageHttpClient.class);
        AdminConfigService service = new AdminConfigService(http, properties());
        when(http.postJson(eq(GATEWAY), eq(ManageApiPaths.CONFIG_ACTIONS), anyMap()))
                .thenReturn(Map.of("operationId", "saved-op", "status", "APPLIED"));

        service.executeGatewayConfigAction("saved-op", "gateway.trace.sampleRate", "0", "1");

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http).postJson(eq(GATEWAY), eq(ManageApiPaths.CONFIG_ACTIONS), body.capture());
        assertEquals(Map.of("operationId", "saved-op", "key", "gateway.trace.sampleRate",
                "expectedValue", "0", "desiredValue", "1"), body.getValue());
    }

    @Test
    void queryUsesReadOnlyReceiptEndpointAndTreatsOnlyNotFoundAsUnknown() throws Exception {
        ManageHttpClient http = mock(ManageHttpClient.class);
        AdminConfigService service = new AdminConfigService(http, properties());
        when(http.getJson(GATEWAY, ManageApiPaths.CONFIG_ACTIONS + "/saved-op"))
                .thenReturn(new ObjectMapper().readTree("{\"operationId\":\"saved-op\",\"status\":\"UNKNOWN\"}"));

        assertEquals("UNKNOWN", service.gatewayConfigAction("saved-op").get("status"));
        verify(http).getJson(GATEWAY, ManageApiPaths.CONFIG_ACTIONS + "/saved-op");
        verify(http, never()).postJson(eq(GATEWAY), anyString(), any());
    }

    @Test
    void configActionNotFoundIsUnknownWhileOtherErrorsPropagate() throws Exception {
        ManageHttpClient http = mock(ManageHttpClient.class);
        AdminConfigService service = new AdminConfigService(http, properties());
        when(http.getJson(eq(GATEWAY), anyString())).thenThrow(new ManageApiCallException(404, "not found"));
        assertEquals("UNKNOWN", service.gatewayConfigAction("missing-op").get("status"));

        reset(http);
        when(http.getJson(eq(GATEWAY), anyString())).thenThrow(new ManageApiCallException(503, "unavailable"));
        assertThrows(ManageApiCallException.class, () -> service.gatewayConfigAction("missing-op"));
    }

    private static AdminProperties properties() {
        AdminProperties properties = new AdminProperties();
        properties.setGatewayUrl(GATEWAY);
        return properties;
    }
}
