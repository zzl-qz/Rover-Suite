package com.rover.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 标记 Rover SPI 扩展实现类的注解
 *
 * 这个类是什么：SPI(服务提供接口)扩展点标识注解。
 * 核心职责：标注某个类为 Rover 框架的扩展实现，value 可给扩展命名，
 * 供框架的插件加载器(plugins 目录/类路径扫描)在运行时实例化并注册。
 * 被谁用：各模块的 SPI 实现类；加载器侧(如 rover-registry 的插件加载逻辑)。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RoverSPI {

    /** 扩展名称，空串表示使用实现类简单名/全限定名作为默认名 */
    String value() default "";
}
