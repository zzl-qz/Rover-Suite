package com.rover.common.manage;

import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigChangeEvent;
import com.rover.common.config.ConfigItem;
import com.rover.common.config.ConfigValues;
import com.rover.common.config.RuntimeConfigManager;
import com.rover.common.constants.HttpConstants;
import com.rover.common.constants.ManageApiPaths;
import com.rover.common.json.JsonCodec;
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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-04 17:10:00
 * Description: 管理 HTTP API 的通用骨架
 */
@Slf4j
public abstract class AbstractManageApi {

    /** 管理 API 路径前缀。 */
    public static final String PREFIX = ManageApiPaths.PREFIX;

    /** 判断路径是否属于管理 API。 */
    public boolean supports(String path) {
        return path != null && (PREFIX.equals(path) || path.startsWith(PREFIX + "/"));
    }

    /** 分发管理请求，统一 JSON 响应与异常映射。 */
    public void handle(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (!authorized(request)) {
            writeJson(ctx, HttpResponseStatus.UNAUTHORIZED,
                    JsonCodec.toJson(Map.of("message", "管理口鉴权失败：token 不匹配")));
            return;
        }
        try {
            if (!dispatch(ctx, request, path)) {
                writeJson(ctx, HttpResponseStatus.NOT_FOUND,
                        JsonCodec.toJson(Map.of("message", "unknown manage path: " + path)));
            }
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    JsonCodec.toJson(Map.of("message", ex.getMessage() == null ? "bad request" : ex.getMessage())));
        } catch (Exception ex) {
            log.warn("{} manage API error, path={}", componentName(), path, ex);
            writeJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    JsonCodec.toJson(Map.of("message", "manage api error")));
        }
    }

    /** 校验管理口 token；未配置时放行，配置后必须匹配 X-Rover-Admin-Token 请求头。 */
    private boolean authorized(FullHttpRequest request) {
        String expected = adminToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String provided = request.headers().get(HttpConstants.ADMIN_TOKEN_HEADER);
        return expected.equals(provided);
    }

    /**
     * 分发到具体端点；已处理返回 true，未命中返回 false（由骨架回 NOT_FOUND）。
     */
    protected abstract boolean dispatch(ChannelHandlerContext ctx, FullHttpRequest request, String path);

    /** 返回当前组件的运行时配置管理器。 */
    protected abstract RuntimeConfigManager configManager();

    /** 组件名，用于日志。 */
    protected abstract String componentName();

    /** 管理口鉴权 token；返回 null 或空串表示关闭鉴权。 */
    protected abstract String adminToken();

    /** 处理 GET/POST /_manage/configs。 */
    protected boolean handleConfigs(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        if (HttpMethod.GET.equals(request.method()) && ManageApiPaths.CONFIGS.equals(path)) {
            writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(configManager().listConfigs()));
            return true;
        }
        if (HttpMethod.POST.equals(request.method()) && ManageApiPaths.CONFIGS.equals(path)) {
            handleUpdateConfig(ctx, request);
            return true;
        }
        return false;
    }

    /** 处理 POST /_manage/configs：解析 key/value 并触发热更新。 */
    protected void handleUpdateConfig(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        String[] kv = JsonCodec.parseKeyValue(body, contentType);
        if (kv == null) {
            throw new IllegalArgumentException("请求体需要 key/value");
        }
        ConfigChangeEvent event = configManager().updateConfig(kv[0], kv[1]);
        ConfigItem item = null;
        for (ConfigItem candidate : configManager().listConfigs()) {
            if (kv[0].equals(candidate.getKey())) {
                item = candidate;
                break;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("key", event.getKey());
        boolean sensitive = item != null && item.isSensitive();
        resp.put("value", sensitive ? ConfigValues.MASKED : event.getNewValue());
        resp.put("oldValue", sensitive ? ConfigValues.MASKED : event.getOldValue());
        resp.put("applyMode", event.getApplyMode() == null ? "" : event.getApplyMode().name());
        resp.put("message", ConfigApplyMode.HOT_RELOAD.equals(event.getApplyMode())
                ? "已热更新并落盘"
                : "已保存，重启后生效");
        if (item != null) {
            resp.put("description", item.getDescription());
            resp.put("hotReloadable", item.isHotReloadable());
        }
        writeJson(ctx, HttpResponseStatus.OK, JsonCodec.toJson(resp));
    }

    /** 写 JSON HTTP 响应。 */
    protected static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpConstants.MEDIA_TYPE_JSON_UTF8);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    /** 写纯文本 HTTP 响应（如 Prometheus 文本格式导出）。 */
    protected static void writeText(ChannelHandlerContext ctx, HttpResponseStatus status, String text,
                                    String contentType) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response);
    }

    /** null 安全转空串。 */
    protected static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
