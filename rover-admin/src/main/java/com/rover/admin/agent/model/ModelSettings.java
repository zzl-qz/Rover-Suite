package com.rover.admin.agent.model;

/**
 * 一次模型配置的不可变快照。
 *
 * {@code apiKey} 是明文，只应存在于内存与请求体中；任何对外响应都必须走掩码，
 * 不得把本对象直接序列化返回。
 */
public record ModelSettings(boolean enabled, String baseUrl, String apiKey, String model,
                            int timeoutSeconds, Source source, KeyState keyState, FastModel fast) {

    /**
     * 便利构造器：不配快速模型。保留它让「只用主模型」的既有用法与测试无需改动。
     */
    public ModelSettings(boolean enabled, String baseUrl, String apiKey, String model,
                         int timeoutSeconds, Source source, KeyState keyState) {
        this(enabled, baseUrl, apiKey, model, timeoutSeconds, source, keyState, FastModel.none());
    }

    /** 配置来源：文件优先，其次由环境变量播种。 */
    public enum Source {
        /** 来自落盘配置文件，页面保存后的常态。 */
        FILE,
        /** 来自环境变量播种，仅在还没有配置文件时出现。 */
        ENV,
        /** 什么都没有配。 */
        NONE
    }

    /** 密钥状态：正常 / 没有密钥（本地无鉴权模型）/ 有密文但解不开。 */
    public enum KeyState {
        OK,
        ABSENT,
        UNREADABLE
    }

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /**
     * 是否"配置了模型"：启用且有服务地址与模型名。
     *
     * 只说"用户配过"，不代表当前可用 —— 能否建成客户端由网关的 {@code available()} 回答。
     */
    public boolean configured() {
        return enabled && notBlank(baseUrl) && notBlank(model);
    }

    /**
     * 是否「配置了快速模型」：与主模型同启用态，且快速模型地址与模型名齐全。
     *
     * 配置后，意图识别 / 目标解析 / 规划这类廉价调用由网关路由到快速模型；
     * 未配置则回退主模型 + 显式禁用思考（{@code fast} 永不为 null，缺失用 {@link FastModel#none()}）。
     */
    public boolean fastConfigured() {
        return enabled && fast != null && fast.configured();
    }

    /** 按密钥明文推导密钥状态，供保存时使用。 */
    public static KeyState keyStateOf(String apiKey) {
        return notBlank(apiKey) ? KeyState.OK : KeyState.ABSENT;
    }

    public static int clampTimeout(int seconds) {
        return Math.min(MAX_TIMEOUT_SECONDS, Math.max(1, seconds));
    }

    /** 完全没有配置模型的起点。 */
    public static ModelSettings none() {
        return new ModelSettings(false, "", "", "", DEFAULT_TIMEOUT_SECONDS, Source.NONE, KeyState.ABSENT,
                FastModel.none());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}