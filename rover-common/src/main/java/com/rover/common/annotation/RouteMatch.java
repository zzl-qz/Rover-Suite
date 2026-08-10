package com.rover.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 标记网关路由匹配路径和请求方法的注解
 *
 * 这个类是什么：标注在网关处理器方法上的路由元数据注解。
 * 核心职责：声明某个方法负责处理哪个 HTTP 路径(path)与请求方法(method)，
 * 由网关启动期的注解扫描器读取并建立「请求 -> 处理方法」的路由表。
 * 被谁用：网关模块(rover-gateway)的处理器注册代码；使用者只需在方法上标注即可。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RouteMatch {

    /** 路由匹配路径，例如 /api/uu/admin/list */
    String path();

    /** 请求方法，例如 GET/POST/PUT/DELETE */
    String method();
}
