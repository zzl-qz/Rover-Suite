package com.rover.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 标记网关路由匹配路径和请求方法的注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RouteMatch {

    String path();

    String method();
}
