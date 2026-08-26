package com.rover.gateway.adapter.nacos;

import java.util.Properties;

/** 创建 Nacos 客户端的函数边界，生产环境使用 Nacos SDK，测试使用内存替身。 */
@FunctionalInterface
interface NacosClientFactory {

    /** 根据 Nacos SDK 配置创建客户端。 */
    NacosClient create(Properties properties) throws Exception;
}
