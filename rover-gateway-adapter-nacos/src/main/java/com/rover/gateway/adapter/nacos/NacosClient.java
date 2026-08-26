package com.rover.gateway.adapter.nacos;

import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;

/** Nacos Naming 的最小客户端边界，隔离 SDK 细节并支持无网络单元测试。 */
interface NacosClient {

    /** 订阅指定服务的实例变化。 */
    void subscribe(String serviceName, String group, EventListener listener) throws Exception;

    /** 拉取指定服务的当前实例列表。 */
    List<Instance> getAllInstances(String serviceName, String group) throws Exception;

    /** 返回 Nacos 客户端当前连接状态。 */
    boolean isConnected();

    /** 取消指定服务的订阅。 */
    void unsubscribe(String serviceName, String group, EventListener listener) throws Exception;

    /** 关闭客户端及其后台资源。 */
    void close() throws Exception;
}
