package com.rover.agent.core.intent;

/**
 * 状态查询问的是哪一类事实：供查询执行侧选择只读能力，避免各处重复维护关键词。
 */
public enum QuerySubject {

    /** 问实例：数量、健康、注册情况 */
    INSTANCE,

    /** 问流量：QPS、请求量、错误率、拒绝计数 */
    METRIC,

    /** 问路由：指向哪个服务、前缀与匹配情况 */
    ROUTE,

    /** 问哪一类事实都不明显：由执行侧按目标是否确定来选 */
    NONE
}