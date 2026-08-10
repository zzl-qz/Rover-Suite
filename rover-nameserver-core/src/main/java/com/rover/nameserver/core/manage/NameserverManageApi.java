package com.rover.nameserver.core.manage;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.json.ManageJson;
import com.rover.common.model.ServiceInstance;
import com.rover.nameserver.core.model.InstanceRecord;
import com.rover.nameserver.core.runtime.NameserverRuntime;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver 管理 HTTP API
 */
@Slf4j
public class NameserverManageApi {

    public static final String PREFIX = "/_manage";

    private final NameserverRuntime runtime;

    public NameserverManageApi(NameserverRuntime runtime) {
        this.runtime = runtime;
    }

    public boolean supports(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        try {
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/status").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, statusJson());
                return;
            }
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/instances").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, instancesJson());
                return;
            }
            if (HttpMethod.GET.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK,
                        ManageJson.configItems(runtime.getConfigManager().listConfigs()));
                return;
            }
            if (HttpMethod.POST.equals(request.method()) && (PREFIX + "/configs").equals(path)) {
                handleUpdateConfig(ctx, request);
                return;
            }
            writeJson(ctx, HttpResponseStatus.NOT_FOUND,
                    ManageJson.object(Map.of("message", "unknown manage path: " + path)));
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    ManageJson.object(Map.of("message", ex.getMessage() == null ? "bad request" : ex.getMessage())));
        } catch (Exception ex) {
            log.warn("Nameserver manage API error, path={}", path, ex);
            writeJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    ManageJson.object(Map.of("message", "manage api error")));
        }
    }

    private void handleUpdateConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        String[] kv = ManageJson.parseKeyValue(body, contentType);
        if (kv == null) {
            throw new IllegalArgumentException("请求体需要 key/value");
        }
        ConfigChangeEvent event = runtime.getConfigManager().updateConfig(kv[0], kv[1]);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", event.getKey());
        resp.put("value", event.getNewValue());
        resp.put("oldValue", event.getOldValue());
        resp.put("applyMode", event.getApplyMode() == null ? "" : event.getApplyMode().name());
        resp.put("message", ConfigApplyMode.HOT_RELOAD.equals(event.getApplyMode())
                ? "已热更新并落盘"
                : "已保存，重启后生效");
        for (ConfigItem item : runtime.getConfigManager().listConfigs()) {
            if (kv[0].equals(item.getKey())) {
                resp.put("description", item.getDescription());
                resp.put("hotReloadable", item.isHotReloadable());
                break;
            }
        }
        writeJson(ctx, HttpResponseStatus.OK, ManageJson.object(resp));
    }

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
        return ManageJson.object(status);
    }

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
        return ManageJson.arrayOfObjects(rows);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }
}
