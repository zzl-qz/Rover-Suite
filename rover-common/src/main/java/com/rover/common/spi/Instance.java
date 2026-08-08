package com.rover.common.spi;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义服务实例暴露的基础信息契约
 */
public interface Instance {

    String getServiceName();

    String getHost();

    int getPort();
}
