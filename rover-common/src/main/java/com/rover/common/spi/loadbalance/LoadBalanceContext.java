package com.rover.common.spi.loadbalance;

import com.rover.common.spi.filter.RequestContext;

import com.rover.common.model.ServiceInstance;
import java.util.List;
import java.util.Objects;

/**
 * Author: Daylight
 * Created: 2026-08-13 15:10:00
 * Description: 负载均衡一次选择的入参：集群键、候选实例、请求上下文与客户端 IP
 */
public final class LoadBalanceContext {

    /** 集群键：动态模式一般是 serviceName，静态模式是 static:routeId */
    private final String clusterKey;

    /** 候选实例（已过滤健康的由调用方保证） */
    private final List<ServiceInstance> instances;

    /** 当前请求上下文，可为 null（单测） */
    private final RequestContext requestContext;

    /** 客户端 IP（网关从连接/头里解析），自定义亲和策略可用 */
    private final String clientIp;

    public LoadBalanceContext(
            String clusterKey,
            List<ServiceInstance> instances,
            RequestContext requestContext,
            String clientIp) {
        this.clusterKey = clusterKey;
        this.instances = instances == null ? List.of() : List.copyOf(instances);
        this.requestContext = requestContext;
        this.clientIp = clientIp;
    }

    public String getClusterKey() {
        return clusterKey;
    }

    public List<ServiceInstance> getInstances() {
        return instances;
    }

    public RequestContext getRequestContext() {
        return requestContext;
    }

    public String getClientIp() {
        return clientIp;
    }

    public boolean hasInstances() {
        return !instances.isEmpty();
    }

    public static LoadBalanceContext of(String clusterKey, List<ServiceInstance> instances) {
        return new LoadBalanceContext(clusterKey, instances, null, null);
    }

    public static LoadBalanceContext of(
            String clusterKey,
            List<ServiceInstance> instances,
            RequestContext requestContext,
            String clientIp) {
        Objects.requireNonNull(clusterKey, "clusterKey");
        return new LoadBalanceContext(clusterKey, instances, requestContext, clientIp);
    }
}
