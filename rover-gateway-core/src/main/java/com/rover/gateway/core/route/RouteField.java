package com.rover.gateway.core.route;

/** 路由管理 API 与 overlay 文件共享的字段名。 */
public enum RouteField {

    ID("id"),
    BUSINESS_PREFIX("businessPrefix"),
    TARGET_URL("targetUrl"),
    TARGET_URLS("targetUrls"),
    TARGETS("targets"),
    STICKY_HEADER("stickyHeader"),
    STRIP_PREFIX("stripPrefix"),

    /** targets 数组内单个元素的字段名。 */
    TARGET_SERVICE_NAME("serviceName"),
    TARGET_GROUP("group"),
    TARGET_WEIGHT("weight");

    private final String jsonName;

    RouteField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
