package com.rover.agent.runtime.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/** 连接时检查实际 DNS 地址；不跟随重定向、不使用系统代理、不重试、不接收 Cookie。 */
final class SafeTelemetryHttp implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "telemetry-deadline"); t.setDaemon(true); return t;
    });
    // OS DNS 解析不保证可中断；最多四个后台解析，调用线程到时返回，积压直接拒绝。
    private final ThreadPoolExecutor dns = new ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "telemetry-dns"); t.setDaemon(true); return t;
    });

    SafeTelemetryHttp() { deadlines.setRemoveOnCancelPolicy(true); }

    JsonNode get(TelemetrySource source, String path, Map<String, String> parameters, long timeoutMillis, int maxBytes) throws IOException {
        if (timeoutMillis <= 0 || maxBytes <= 0) { throw new IOException("遥测读取预算耗尽"); }
        String query = parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        URI uri = URI.create(source.endpoint().toString().replaceAll("/$", "") + path + "?" + query);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        DnsResolver resolver = boundedResolver(source.endpoint().getHost(), source.allowedAddresses(), deadline);
        // IP literal may bypass the HTTP library resolver; validate it independently too.
        String host = source.endpoint().getHost().replace("[", "").replace("]", "");
        if (host.matches("[0-9.]+") || host.contains(":")) { validateAddress(literalAddress(host), source.allowedAddresses()); }
        var timeout = Timeout.ofMilliseconds(timeoutMillis);
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build()).build();
        HttpGet request = new HttpGet(uri);
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout).setResponseTimeout(timeout).build());
        request.setHeader("Accept", "application/json");
        if (!source.bearerToken().isEmpty()) { request.setHeader("Authorization", "Bearer " + source.bearerToken()); }
        if (!source.tenantId().isEmpty()) { request.setHeader("X-Scope-OrgID", source.tenantId()); }
        var cancel = deadlines.schedule(request::cancel, timeoutMillis, TimeUnit.MILLISECONDS);
        try (var client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching().disableContentCompression().build()) {
            return client.execute(request, response -> {
                if (response.getCode() != 200) { request.cancel(); throw new IOException("遥测 HTTP 状态 " + response.getCode()); }
                var entity = response.getEntity();
                if (entity == null || entity.getContentLength() > maxBytes || response.containsHeader("Content-Encoding")) {
                    request.cancel(); throw new IOException("遥测响应体超限或编码不支持");
                }
                try (var in = entity.getContent()) {
                    byte[] bytes = in.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) { request.cancel(); throw new IOException("遥测响应体超限"); }
                    return JSON.readTree(bytes);
                }
            });
        } finally { cancel.cancel(false); }
    }

    private DnsResolver boundedResolver(String host, Set<String> allowed, long deadline) {
        DnsResolver guarded = resolver(host, allowed);
        return new DnsResolver() {
            public InetAddress[] resolve(String name) throws UnknownHostException {
                java.util.concurrent.Future<InetAddress[]> lookup = null;
                try {
                    lookup = dns.submit(() -> guarded.resolve(name));
                    return lookup.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new UnknownHostException("遥测 DNS 查询中断"); }
                catch (ExecutionException | java.util.concurrent.TimeoutException | java.util.concurrent.RejectedExecutionException ex) {
                    throw new UnknownHostException("遥测 DNS 查询失败或预算耗尽");
                } finally { if (lookup != null) { lookup.cancel(true); } }
            }
            public String resolveCanonicalHostname(String name) throws UnknownHostException { resolve(name); return name; }
        };
    }

    static DnsResolver resolver(String expectedHost, Set<String> allowed) {
        return new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) { throw new UnknownHostException("遥测主机不匹配"); }
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (var address : addresses) {
                    try { validateAddress(address, allowed); }
                    catch (IllegalArgumentException ex) { throw new UnknownHostException("遥测 DNS 地址不在允许范围"); }
                }
                return addresses;
            }
            public String resolveCanonicalHostname(String host) throws UnknownHostException { resolve(host); return host; }
        };
    }

    static InetAddress literalAddress(String value) {
        if (value == null || !(value.matches("[0-9.]+") || value.contains(":")) || value.contains("%")) {
            throw new IllegalArgumentException("地址白名单只能包含 IP 字面量");
        }
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException ex) { throw new IllegalArgumentException("地址白名单中的 IP 无效"); }
    }

    static void validateAddress(InetAddress address, Set<String> allowed) {
        byte[] bytes = address.getAddress();
        boolean metadata = bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) == 100
                && (bytes[2] & 255) == 100 && (bytes[3] & 255) == 200
                || address.equals(literalAddress("fd00:ec2::254"));
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress() || metadata
                || allowed.stream().noneMatch(a -> Arrays.equals(literalAddress(a).getAddress(), bytes))) {
            throw new IllegalArgumentException("遥测目标地址被拒绝");
        }
    }

    private static String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public void close() { deadlines.shutdownNow(); dns.shutdownNow(); }
}
