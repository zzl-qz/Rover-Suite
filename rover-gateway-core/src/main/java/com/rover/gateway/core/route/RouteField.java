package com.rover.gateway.core.route;

/** 路由管理 API 与 overlay 文件共享的字段名。 */
public enum RouteField {

    ID("id"),
    BUSINESS_PREFIX("businessPrefix"),
    TARGET_URL("targetUrl"),
    TARGET_URLS("targetUrls"),
    SERVICE_NAME("serviceName"),
    GROUP("group"),
    STRIP_PREFIX("stripPrefix");

    private final String jsonName;

    RouteField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
