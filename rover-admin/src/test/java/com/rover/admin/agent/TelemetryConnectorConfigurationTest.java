package com.rover.admin.agent;

import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.runtime.connector.TelemetryConnectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryConnectorConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(TelemetryConnectorConfiguration.class)
            .withBean(com.rover.agent.core.port.RouteControlPort.class, () -> {
                var control = org.mockito.Mockito.mock(com.rover.agent.core.port.RouteControlPort.class);
                org.mockito.Mockito.when(control.resourceNamespace()).thenReturn("internal-namespace"); return control;
            });
    private String[] sourceProperties() {
        return new String[]{"rover.agent.telemetry.sources[0].id=logs", "rover.agent.telemetry.sources[0].kind=LOKI",
                "rover.agent.telemetry.sources[0].endpoint=https://logs.example.invalid", "rover.agent.telemetry.sources[0].allowed-addresses[0]=10.20.0.10",
                "rover.agent.telemetry.sources[0].scope.organization-id=org", "rover.agent.telemetry.sources[0].scope.team-id=ops",
                "rover.agent.telemetry.sources[0].scope.environment-id=test", "rover.agent.telemetry.sources[0].control-target-id=gateway",
                "rover.agent.telemetry.sources[0].namespace=apps", "rover.agent.telemetry.sources[0].bearer-token=fixture-secret",
                "rover.agent.telemetry.sources[0].bindings[0].target-type=ROUTE", "rover.agent.telemetry.sources[0].bindings[0].target=/orders",
                "rover.agent.telemetry.sources[0].bindings[0].service=orders"};
    }
    @Test void disabledByDefault() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBean(TelemetryConnectors.class).capabilities().isEmpty()); });
    }
    @Test void immutableSourcePropertiesBindWithoutExposingSecrets() {
        runner.withPropertyValues(sourceProperties()).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(java.util.Set.of(AgentCapability.APPLICATION_LOG_QUERY), context.getBean(TelemetryConnectors.class).capabilities());
            var properties = context.getBean(TelemetryConnectorConfiguration.Properties.class);
            assertFalse(properties.toString().contains("fixture-secret")); assertFalse(properties.sources().toString().contains("fixture-secret"));
        });
    }
    @Test void unsafeConfigurationFailsAtStartup() {
        runner.withPropertyValues(sourceProperties()).withPropertyValues("rover.agent.telemetry.sources[0].allowed-addresses[0]=169.254.169.254")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void customLimitsAreValidatedRatherThanSilentlyClamped() {
        runner.withPropertyValues(sourceProperties()).withPropertyValues("rover.agent.telemetry.limits.timeout-seconds=15",
                "rover.agent.telemetry.limits.max-window-seconds=3600", "rover.agent.telemetry.limits.max-rows=150", "rover.agent.telemetry.limits.page-size=50",
                "rover.agent.telemetry.limits.max-pages=99", "rover.agent.telemetry.limits.max-response-bytes=1048576", "rover.agent.telemetry.limits.freshness-seconds=120")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
