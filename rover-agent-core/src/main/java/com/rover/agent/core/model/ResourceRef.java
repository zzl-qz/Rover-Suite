package com.rover.agent.core.model;

/** 资源名称用于定位，UID 用于识别重建；generation 表示资源身份代次。 */
public record ResourceRef(ResourceScope scope, String controlTargetId, String kind, String resourceId,
                          String resourceUid, long generation) {
    public ResourceRef {
        if (scope == null || blank(controlTargetId) || blank(kind) || blank(resourceId)
                || blank(resourceUid) || generation <= 0) {
            throw new IllegalArgumentException("资源身份必须包含范围、管理目标、类型、ID、UID 与正代次");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
