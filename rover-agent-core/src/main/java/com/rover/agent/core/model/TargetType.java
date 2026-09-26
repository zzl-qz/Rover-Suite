package com.rover.agent.core.model;

/** 调查对象的类型。 */
public enum TargetType {

    /** 一条 Gateway 路由（以请求路径标识） */
    ROUTE,

    /** 一个后端服务（以服务名标识） */
    SERVICE,

    /** 一个服务实例（以 ip:port 标识） */
    INSTANCE,

    /** 无法从用户问题中确定调查对象 */
    UNKNOWN
}