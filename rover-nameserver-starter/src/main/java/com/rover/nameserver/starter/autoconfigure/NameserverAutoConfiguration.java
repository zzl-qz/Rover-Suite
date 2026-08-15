package com.rover.nameserver.starter.autoconfigure;

import com.rover.common.util.HostPort;
import com.rover.nameserver.client.connection.NameserverClient;
import com.rover.nameserver.client.connection.NameserverClientOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: Nameserver Client 自动装配
 */
@AutoConfiguration
@ConditionalOnClass(NameserverClient.class)
@ConditionalOnProperty(prefix = "rover.nameserver", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RoverNameserverProperties.class)
public class NameserverAutoConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean // 容器中无同类型 Bean 时才创建
    public NameserverClient nameserverClient(RoverNameserverProperties properties) {
        HostPort address = HostPort.require(properties.getAddress(), "rover.nameserver.address");
        NameserverClientOptions options = NameserverClientOptions.builder()
                .host(address.host())
                .port(address.port())
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .requestTimeoutMs(properties.getRequestTimeoutMs())
                .heartbeatIntervalMs(properties.getHeartbeatIntervalMs())
                .autoHeartbeat(true)
                .autoReconnect(properties.isAutoReconnect())
                .reconnectIntervalMs(properties.getReconnectIntervalMs())
                .build();
        return new NameserverClient(options);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoverNameserverLifecycle roverNameserverLifecycle(
            NameserverClient nameserverClient,
            RoverNameserverProperties properties,
            Environment environment) {
        return new RoverNameserverLifecycle(nameserverClient, properties, environment);
    }
}
