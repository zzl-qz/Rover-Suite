package com.rover.common.constants;

/** Rover 各组件共享的 HTTP 协议常量。 */
public final class HttpConstants {

    private HttpConstants() {
    }

    public static final String SCHEME_HTTP = "http";
    public static final String SCHEME_HTTPS = "https";
    public static final int DEFAULT_HTTP_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;

    public static final String ADMIN_TOKEN_HEADER = "X-Rover-Admin-Token";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Rover-Trace-Id";
    public static final String REJECT_REASON_HEADER = "X-Rover-Reject-Reason";
    public static final String REJECT_INFLIGHT_LIMIT = "INFLIGHT_LIMIT";
    public static final String REJECT_NO_UPSTREAM = "NO_UPSTREAM";
    public static final String REJECT_CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String REJECT_CONNECTION_BUSY = "CONNECTION_BUSY";
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    public static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    public static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_PUT = "PUT";
    public static final String METHOD_DELETE = "DELETE";
    public static final String METHOD_PATCH = "PATCH";
    public static final String METHOD_OPTIONS = "OPTIONS";

    public static final String MEDIA_TYPE_JSON = "application/json";
    public static final String MEDIA_TYPE_JSON_UTF8 = "application/json; charset=UTF-8";
    public static final String MEDIA_TYPE_FORM = "application/x-www-form-urlencoded";
    public static final String MEDIA_TYPE_TEXT_UTF8 = "text/plain; charset=UTF-8";
    public static final String MEDIA_TYPE_PROMETHEUS = "text/plain; version=0.0.4; charset=UTF-8";
}
