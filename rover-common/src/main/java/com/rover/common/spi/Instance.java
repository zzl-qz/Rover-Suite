/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：定义服务实例暴露的基础信息契约
 */
package com.rover.common.spi;

public interface Instance {

    String getServiceName();

    String getHost();

    int getPort();
}
