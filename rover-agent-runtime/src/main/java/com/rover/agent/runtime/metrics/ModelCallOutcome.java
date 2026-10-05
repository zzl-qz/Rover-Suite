package com.rover.agent.runtime.metrics;

import java.util.Locale;

/** 模型调用结局的有限枚举，可作为指标标签；非 OK 结果由调用方按约定回退。 */
public enum ModelCallOutcome {

    /** 拿到非空响应，且调用方判定可用。 */
    OK,
    /** 未配置模型：不问不打（本地开发与纯规则部署的常态）。 */
    NOT_CONFIGURED,
    /** 配置了但客户端没建起来（如缺少密钥、地址非法）。 */
    UNAVAILABLE,
    /** 超过本场景的等待上限（含重试一次后仍未返回）。 */
    TIMEOUT,
    /** 调用抛出非超时异常（鉴权失败、模型不存在、参数错误等）。 */
    ERROR,
    /** 调用成功但没有内容。 */
    EMPTY,
    /** 有内容但不符合输出契约（越界取值、缺必需字段），已整条丢弃。 */
    REJECTED;

    /** 是否拿到了可用结果；只有 {@link #OK} 为真。 */
    public boolean ok() {
        return this == OK;
    }

    /** 指标标签取值：小写，与 model 标签的规范化风格一致。 */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
