package com.rover.agent.runtime.llm;

/** 宿主 HTTP 错误边界；只传状态和有界重试间隔，不传响应正文、地址或凭证。 */
public final class ModelProviderFailure extends RuntimeException {
    private final int status;
    private final int retryAfterSeconds;
    public ModelProviderFailure(int status,int retryAfterSeconds) {
        super("模型服务 HTTP "+status);this.status=status;this.retryAfterSeconds=Math.min(3600,Math.max(0,retryAfterSeconds));
    }
    public int status() { return status; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
