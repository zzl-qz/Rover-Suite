package com.rover.gateway.core.server;

import com.rover.common.constants.HttpConstants;
import com.rover.gateway.core.config.GatewayDefaults;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Description: CORS 配置：开关、允许的 Origin/方法/头、预检缓存时长
 */
@Data
public class CorsSettings {

    /** 是否启用 CORS 处理，默认关闭（同源部署无需配置）。 */
    private boolean enabled = false;

    /** 允许的 Origin 列表；为空或包含 * 表示允许任意来源。 */
    private List<String> allowedOrigins = new ArrayList<>();

    /** 允许跨域请求的方法。 */
    private List<String> allowedMethods =
            new ArrayList<>(List.of(
                    HttpConstants.METHOD_GET,
                    HttpConstants.METHOD_POST,
                    HttpConstants.METHOD_PUT,
                    HttpConstants.METHOD_DELETE,
                    HttpConstants.METHOD_PATCH,
                    HttpConstants.METHOD_OPTIONS));

    /** 允许跨域请求携带的头；* 表示任意。 */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

    /** 预检（OPTIONS）结果缓存秒数。 */
    private long maxAgeSeconds = GatewayDefaults.CORS_MAX_AGE_SECONDS;

    /** 是否允许携带凭证（Cookie/Authorization 等）；开启后不允许用 * 通配 Origin。 */
    private boolean credentials = false;

    /**
     * 判断给定 Origin 是否允许，并给出要写入响应头的值。
     *
     * @param origin 请求携带的 Origin
     * @return "*"（允许任意）或回显原 Origin；不允许时返回 null
     */
    public String resolveAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.stream()
                .anyMatch(item -> item != null && "*".equals(item.trim()))) {
            return "*";
        }
        for (String allowed : allowedOrigins) {
            if (allowed != null && origin.equalsIgnoreCase(allowed.trim())) {
                return origin;
            }
        }
        return null;
    }

    /** 拼接 Access-Control-Allow-Methods。 */
    public String joinAllowedMethods() {
        // 为空默认允许所有
        if (allowedMethods == null || allowedMethods.isEmpty()) {
            return String.join(", ", List.of(
                    HttpConstants.METHOD_GET,
                    HttpConstants.METHOD_POST,
                    HttpConstants.METHOD_PUT,
                    HttpConstants.METHOD_DELETE,
                    HttpConstants.METHOD_PATCH,
                    HttpConstants.METHOD_OPTIONS));
        }
        return String.join(", ", allowedMethods);
    }

    /** 拼接 Access-Control-Allow-Headers。 */
    public String joinAllowedHeaders() {
        if (allowedHeaders == null || allowedHeaders.isEmpty()) {
            return "*";
        }
        return String.join(", ", allowedHeaders);
    }
}
