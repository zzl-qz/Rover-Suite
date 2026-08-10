package com.rover.nameserver.starter.autoconfigure;

import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.client.connection.NameserverClient;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: 应用就绪后自动注册，关闭时注销
 */
@Slf4j
public class RoverNameserverLifecycle implements ApplicationListener<ApplicationReadyEvent> {

    private final NameserverClient client;
    private final RoverNameserverProperties properties;
    private final Environment environment;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rover-nameserver-register-retry");
        thread.setDaemon(true);
        return thread;
    });

    private volatile RegisterRequest registerRequest;
    private volatile ScheduledFuture<?> retryFuture;

    public RoverNameserverLifecycle(
            NameserverClient client,
            RoverNameserverProperties properties,
            Environment environment) {
        this.client = client;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            registerRequest = buildRegisterRequest(event.getApplicationContext());
            client.start();
            tryRegister();
            if (!registered.get()) {
                // 首次失败也继续重试，Nameserver 晚启动时能自动补上
                retryFuture = retryScheduler.scheduleWithFixedDelay(
                        this::tryRegister,
                        properties.getRegisterRetryIntervalMs(),
                        properties.getRegisterRetryIntervalMs(),
                        TimeUnit.MILLISECONDS);
            }
        } catch (Exception ex) {
            log.error("Rover Nameserver 自动注册启动失败", ex);
        }
    }

    @PreDestroy
    public void destroy() {
        if (retryFuture != null) {
            retryFuture.cancel(false);
        }
        retryScheduler.shutdownNow();

        if (registerRequest != null && registered.get() && client.isActive()) {
            try {
                client.unregister(registerRequest.getServiceName(), registerRequest.getInstanceId());
                log.info("已注销实例: {}#{}", registerRequest.getServiceName(), registerRequest.getInstanceId());
            } catch (Exception ex) {
                log.warn("优雅注销失败，将依赖断连清理: {}#{}",
                        registerRequest.getServiceName(), registerRequest.getInstanceId(), ex);
            }
        }
        client.shutdown();
    }

    private void tryRegister() {
        if (registered.get() || registerRequest == null) {
            return;
        }
        try {
            if (!client.isActive()) {
                log.debug("Nameserver 尚未连通，稍后重试注册");
                return;
            }
            client.register(registerRequest);
            registered.set(true);
            if (retryFuture != null) {
                retryFuture.cancel(false);
            }
            log.info("实例注册成功: {}#{} -> {}:{}",
                    registerRequest.getServiceName(),
                    registerRequest.getInstanceId(),
                    registerRequest.getHost(),
                    registerRequest.getPort());
        } catch (Exception ex) {
            log.warn("注册失败，{}ms 后重试: {}", properties.getRegisterRetryIntervalMs(), ex.getMessage());
        }
    }

    private RegisterRequest buildRegisterRequest(ApplicationContext context) {
        String serviceName = resolveServiceName();
        String host = resolveHost();
        int port = resolvePort(context);
        String instanceId = StringUtils.hasText(properties.getInstanceId())
                ? properties.getInstanceId().trim()
                : host + ":" + port;

        RegisterRequest request = new RegisterRequest();
        request.setServiceName(serviceName);
        request.setHost(host);
        request.setPort(port);
        request.setInstanceId(instanceId);
        request.setRegisterTime(System.currentTimeMillis());
        request.setWeight(properties.getWeight());
        request.setGroup(properties.getGroup());
        request.setZone(properties.getZone());
        request.setEphemeral(properties.isEphemeral());
        request.setMetadata(properties.getMetadata());
        return request;
    }

    private String resolveServiceName() {
        if (StringUtils.hasText(properties.getServiceName())) {
            return properties.getServiceName().trim();
        }
        String appName = environment.getProperty("spring.application.name");
        if (StringUtils.hasText(appName)) {
            return appName.trim();
        }
        throw new IllegalStateException("请配置 rover.nameserver.service-name 或 spring.application.name");
    }

    private String resolveHost() {
        if (StringUtils.hasText(properties.getHost())) {
            return properties.getHost().trim();
        }
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            log.warn("自动探测 host 失败，回退 127.0.0.1", ex);
            return "127.0.0.1";
        }
    }

    private int resolvePort(ApplicationContext context) {
        if (properties.getPort() != null && properties.getPort() > 0) {
            return properties.getPort();
        }
        if (context instanceof WebServerApplicationContext webContext) {
            int port = webContext.getWebServer().getPort();
            if (port > 0) {
                return port;
            }
        }
        return Integer.parseInt(environment.getProperty("server.port", "8080"));
    }
}
