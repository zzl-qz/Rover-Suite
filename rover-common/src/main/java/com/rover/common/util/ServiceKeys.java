package com.rover.common.util;

/** 服务注册、订阅和路由内部复合键的统一生成入口。 */
public final class ServiceKeys {

    private static final char SEPARATOR = '\u001f';

    private ServiceKeys() {
    }

    public static String serviceGroup(String serviceName, String group) {
        return normalize(serviceName) + SEPARATOR + normalize(group);
    }

    public static String serviceInstance(String serviceName, String instanceId) {
        return normalize(serviceName) + SEPARATOR + normalize(instanceId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
