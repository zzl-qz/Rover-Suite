package com.rover.nameserver.core.manage;

import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.constants.RoverComponent;
import com.rover.common.json.JsonCodec;
import com.rover.common.manage.AbstractManageApi;
import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Author: Daylight
 * Created: 2026-08-11 11:20:00
 * Description: Nameserver 管理 HTTP API，暴露 /_manage/status 与 /instances 查询，configs 与 JSON/异常处理由基类提供
 */
public class NameserverManageApi extends AbstractManageApi {

    /** 运行时引用，读注册表/健康检查/配置等状态。 */
    private final NameserverRuntime runtime;

    public NameserverManageApi(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    protected String componentName() {
        return RoverComponent.NAMESERVER.displayName();
    }

    @Override
    protected RuntimeConfigManager configManager() {
        return runtime.getConfigManager();
    }

    @Override
    protected String adminToken() {
        return runtime.getOptions().getAdminToken();
    }

    @Override
    protected boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.STATUS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, statusJson());
            return true;
        }
        // snapshot 是 /instances 的子路径：先判它，避免将来前缀匹配改动时被 /instances 分支先吃掉
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.INSTANCES_SNAPSHOT.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, instancesSnapshotJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.INSTANCES.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, instancesJson());
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS_LIVE.equals(path)) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            int range = ManageApiPaths.clampLiveRange(firstQuery(decoder, ManageApiPaths.PARAM_RANGE));
            writeJson(ctx, HttpResponseStatus.OK,
                    runtime.getMetrics().liveJson(runtime.getRegistry(), range));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.METRICS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetrics().snapshotJson(runtime.getRegistry()));
            return true;
        }
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.EVENTS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, runtime.getMetrics().eventsJson());
            return true;
        }
        return handleConfigs(ctx, request, path);
    }

    /** 组装 /status 响应：组件状态、端口、实例数、健康检查参数等。 */
    private String statusJson() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("component", RoverComponent.NAMESERVER.id());
        status.put("up", true);
        status.put("port", runtime.getOptions().getPort());
        status.put("managePort", runtime.getOptions().getManagePort());
        status.put("clientApiEnabled", runtime.getOptions().isClientApiEnabled());
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
            rows.add(instanceJson(record));
        }
        return JsonCodec.toJson(rows);
    }

    /**
     * 组装 /instances/snapshot 响应：带 revision/epoch 的可核对实例视图。
     *
     * <p>Agent 用它回答「我看到的实例、版本，和注册中心当前是不是同一份」。因此这里
     * 直接复用注册表自己的 revision（按服务维度单调递增），不另造版本号——两套版本号
     * 只会让「谁更新」无从判断。顶层 revision 取各服务 revision 之和，任一服务变更
     * 都会让它变化，一次比较即可判断注册视图是否变过。
     */
    private String instancesSnapshotJson() {
        List<InstanceRecord> records = runtime.getRegistry().listAllRecords();
        // 按 service+group 归并；service 的 revision 沿用注册表口径（service 维度，不细分 group）
        Map<String, Map<String, Object>> services = new TreeMap<>();
        for (InstanceRecord record : records) {
            ServiceInstance instance = record.getInstance();
            String serviceName = nullToEmpty(instance.getServiceName());
            String group = nullToEmpty(instance.getGroup());
            Map<String, Object> row = services.computeIfAbsent(serviceName + "\u0000" + group, key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("serviceName", serviceName);
                created.put("group", group);
                created.put("revision", runtime.getRegistry().revisionOf(serviceName));
                created.put("instances", new ArrayList<Map<String, Object>>());
                return created;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) row.get("instances");
            instances.add(instanceJson(record));
        }

        long aggregateRevision = 0L;
        for (Map<String, Object> row : services.values()) {
            aggregateRevision += ((Number) row.get("revision")).longValue();
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("revision", aggregateRevision);
        root.put("epoch", nullToEmpty(runtime.getPushService().getEpoch()));
        root.put("count", records.size());
        root.put("services", new ArrayList<>(services.values()));
        return JsonCodec.toJson(root);
    }

    /** 单条实例 JSON 行：/instances 与 /instances/snapshot 共用，避免两处字段口径漂移。 */
    private static Map<String, Object> instanceJson(InstanceRecord record) {
        ServiceInstance instance = record.getInstance();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serviceName", nullToEmpty(instance.getServiceName()));
        row.put("instanceId", nullToEmpty(instance.getInstanceId()));
        row.put("host", nullToEmpty(instance.getHost()));
        row.put("port", instance.getPort());
        row.put("group", nullToEmpty(instance.getGroup()));
        // healthy 必须是真实心跳健康状态，不能用其它字段冒充
        row.put("healthy", instance.isHealthy());
        row.put("ephemeral", instance.isEphemeral());
        row.put("weight", instance.getWeight());
        row.put("lastHeartbeatMillis", record.getLastHeartbeatMillis());
        return row;
    }

    private static String firstQuery(QueryStringDecoder decoder, String name) {
        List<String> values = decoder.parameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
