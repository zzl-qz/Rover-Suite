/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：标记 Rover SPI 扩展实现类的注解
 */
package com.rover.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RoverSPI {

    String value() default "";
}
