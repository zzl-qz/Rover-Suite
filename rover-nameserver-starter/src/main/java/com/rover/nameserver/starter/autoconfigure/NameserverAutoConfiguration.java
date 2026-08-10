package com.rover.nameserver.starter.autoconfigure;

import com.rover.nameserver.client.connection.NameserverClient;
import com.rover.nameserver.client.connection.NameserverClientOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

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
    @ConditionalOnMissingBean
    public NameserverClient nameserverClient(RoverNameserverProperties properties) {
        Address address = Address.parse(properties.getAddress());
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

    private record Address(String host, int port) {
        static Address parse(String raw) {
            if (!StringUtils.hasText(raw)) {
                throw new IllegalArgumentException("rover.nameserver.address 不能为空");
            }
            String value = raw.trim();
            int idx = value.lastIndexOf(':');
            if (idx <= 0 || idx == value.length() - 1) {
                throw new IllegalArgumentException("rover.nameserver.address 格式应为 host:port，当前=" + raw);
            }
            String host = value.substring(0, idx).trim();
            int port = Integer.parseInt(value.substring(idx + 1).trim());
            if (!StringUtils.hasText(host) || port <= 0) {
                throw new IllegalArgumentException("rover.nameserver.address 非法: " + raw);
            }
            return new Address(host, port);
        }
    }
}
