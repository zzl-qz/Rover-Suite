package com.rover.admin.agent;

import com.rover.agent.runtime.connector.TelemetryConnectors;
import com.rover.agent.runtime.connector.TelemetrySource;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryConnectorConfiguration.Properties.class)
public class TelemetryConnectorConfiguration {
    @ConfigurationProperties("rover.agent.telemetry")
    public record Properties(List<TelemetrySource> sources, TelemetryConnectors.Limits limits) {
        @Override public String toString() { return "TelemetryProperties[redacted]"; }
    }

    @Bean(destroyMethod = "close")
    public TelemetryConnectors telemetryReadPort(Properties properties, com.rover.agent.core.port.RouteControlPort control) {
        return new TelemetryConnectors(properties.sources(), properties.limits(), control.resourceNamespace());
    }
}
