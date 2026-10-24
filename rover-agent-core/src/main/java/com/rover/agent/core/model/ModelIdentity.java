package com.rover.agent.core.model;

/** 客户端创建时冻结的公开身份；无凭证、完整地址或提示词。 */
public record ModelIdentity(String provider, String requestedModel, String endpointDigest,
                            String configurationRevision, int timeoutSeconds, boolean thinkingEnabled) {
    public static ModelIdentity unknown() { return new ModelIdentity("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", 0, false); }
}
