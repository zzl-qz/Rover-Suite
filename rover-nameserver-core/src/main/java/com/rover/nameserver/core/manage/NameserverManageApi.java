package com.rover.nameserver.core.manage;

import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.json.JsonCodec;
import com.rover.common.manage.AbstractManageApi;
import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nameserver 管理 HTTP API。
 *
 * 这个类是什么：管理口的 REST 风格路由与 JSON 响应层。
 * 核心职责：暴露 /_manage/status、/instances 查询；/configs 通用端点与
 * JSON/异常处理由 AbstractManageApi 提供。
 * 被谁用：NameserverHttpManageServer 把 HTTP 请求转进来。
 */
public class NameserverManageApi extends AbstractManageApi {

    /** 运行时引用，读注册表/健康检查/配置等状态。 */
    private final NameserverRuntime runtime;

    public NameserverManageApi(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    protected String componentName() {
        return "Nameserver";
    }

    @Override
    protected RuntimeConfigManager configManager() {
        return runtime.getConfigManager();
    }

    @Override
    protected boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/status").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, statusJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/instances").equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, instancesJson());
            return true;
        }
        return handleConfigs(ctx, request, path);
    }

    /** 组装 /status 响应：组件状态、端口、实例数、健康检查参数等。 */
    private String statusJson() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", "nameserver");
        status.put("up", true);
        status.put("port", runtime.getOptions().getPort());
        status.put("managePort", runtime.getOptions().getManagePort());
        status.put("instanceCount", runtime.getRegistry().listAllRecords().size());
        status.put("pushEnabled", runtime.getPushService().isPushEnabled());
        status.put("healthCheckIntervalMillis", runtime.getHealthChecker().getCheckIntervalMillis());
        status.put("heartbeatTimeoutMillis", runtime.getHealthChecker().getHeartbeatTimeoutMillis());
        status.put("instanceExpireMillis", runtime.getHealthChecker().getInstanceExpireMillis());
        status.put("writeAckMode", runtime.getOptions().getWriteAckMode().name());
        status.put("configOverlay", runtime.getConfigManager().getOverlayStore().getPath().toString());
        return JsonCodec.toJson(status);
    }

    /** 组装 /instances 响应：注册表全部实例的 JSON 数组。 */
    private String instancesJson() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InstanceRecord record : runtime.getRegistry().listAllRecords()) {
            ServiceInstance instance = record.getInstance();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceName", nullToEmpty(instance.getServiceName()));
            row.put("instanceId", nullToEmpty(instance.getInstanceId()));
            row.put("host", nullToEmpty(instance.getHost()));
            row.put("port", instance.getPort());
            row.put("group", nullToEmpty(instance.getGroup()));
            row.put("healthy", instance.isHealthy());
            row.put("ephemeral", instance.isEphemeral());
            row.put("weight", instance.getWeight());
            row.put("lastHeartbeatMillis", record.getLastHeartbeatMillis());
            rows.add(row);
        }
        return JsonCodec.toJson(rows);
    }
}
