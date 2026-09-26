package com.rover.agent.core.model;

/**
 * 被调查的资源对象：把「只能按请求路径提问」泛化为路由 / 服务 / 实例三种目标。
 *
 * @param type  目标类型
 * @param value 目标取值：路由为请求路径，服务为服务名，实例为 {@code ip:port}；无法确定时为空串
 */
public record ResourceTarget(TargetType type, String value) {

    public ResourceTarget {
        value = value == null ? "" : value.trim();
    }

    /** 以请求路径标识的路由目标。 */
    public static ResourceTarget route(String path) {
        return new ResourceTarget(TargetType.ROUTE, path);
    }

    /** 以服务名标识的服务目标。 */
    public static ResourceTarget service(String serviceName) {
        return new ResourceTarget(TargetType.SERVICE, serviceName);
    }

    /** 以 {@code ip:port} 标识的实例目标。 */
    public static ResourceTarget instance(String address) {
        return new ResourceTarget(TargetType.INSTANCE, address);
    }

    /** 未能确定的目标；需要向用户澄清，不允许猜测。 */
    public static ResourceTarget unknown() {
        return new ResourceTarget(TargetType.UNKNOWN, "");
    }
}