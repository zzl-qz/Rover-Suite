package com.rover.gateway.adapter.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;
import java.util.Properties;

/** Nacos SDK 的薄封装，避免 SDK 类型渗透到发现逻辑。 */
final class SdkNacosClient implements NacosClient {

    private final NamingService namingService;

    private SdkNacosClient(NamingService namingService) {
        this.namingService = namingService;
    }

    /** 使用 Nacos SDK 创建实际客户端。 */
    static NacosClient create(Properties properties) throws Exception {
        return new SdkNacosClient(NacosFactory.createNamingService(properties));
    }

    @Override
    public void subscribe(String serviceName, String group, EventListener listener) throws Exception {
        namingService.subscribe(serviceName, group, listener);
    }

    @Override
    public List<Instance> getAllInstances(String serviceName, String group) throws Exception {
        return namingService.getAllInstances(serviceName, group);
    }

    @Override
    public boolean isConnected() {
        return "UP".equalsIgnoreCase(namingService.getServerStatus());
    }

    @Override
    public void unsubscribe(String serviceName, String group, EventListener listener) throws Exception {
        namingService.unsubscribe(serviceName, group, listener);
    }

    @Override
    public void close() throws Exception {
        namingService.shutDown();
    }
}
