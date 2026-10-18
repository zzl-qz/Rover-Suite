package com.rover.agent.core.model;

/** 服务端确定的组织、团队与环境；不从模型文本或路由 group 推导。 */
public record ResourceScope(String organizationId, String teamId, String environmentId) {
    public ResourceScope {
        organizationId = required(organizationId);
        teamId = required(teamId);
        environmentId = required(environmentId);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("资源范围标识必须为 1~128 字符");
        }
        return value.trim();
    }
}
