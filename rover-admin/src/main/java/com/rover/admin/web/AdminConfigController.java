package com.rover.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.admin.service.AdminConfigService;
import com.rover.admin.service.ConfigUpdateResult;
import com.rover.common.config.ConfigApplyMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * Author: Daylight
 * Created: 2026-08-12 17:10:00
 * Description: Admin 控制台：状态、实例、路由管理与配置热更新页面
 */
@Controller
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
        String discoveryType = configService.discoveryType();
        boolean nameserverMode = "NAMESERVER".equalsIgnoreCase(discoveryType);
        // 不用 formatted：CSS 里有很多 %，会炸
        return INDEX_HTML
                .replace("{{DISCOVERY}}", escape(discoveryType))
                .replace("{{MODE_HINT}}", nameserverMode
                        ? "请填 serviceName"
                        : "请填 targetUrl 或 targetUrls（多机逗号分隔，可带|权重）")
                .replace("{{SERVICE_STAR}}", nameserverMode ? "*" : "")
                .replace("{{SERVICE_REQ}}", nameserverMode ? "required" : "")
                // 静态模式 targetUrl / targetUrls 二选一即可，不再强制 targetUrl required
                .replace("{{TARGET_STAR}}", nameserverMode ? "" : "")
                .replace("{{TARGET_REQ}}", "");
    }

    private static final String INDEX_HTML = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Rover Admin</title>
                <script src="https://unpkg.com/htmx.org@1.9.12"></script>
                <style>
                    :root {
                        --bg: #f3f4f1;
                        --ink: #1c1f1a;
                        --muted: #5f675c;
                        --card: #ffffff;
                        --line: #d9ddd4;
                        --accent: #1f6f5b;
                        --accent-soft: #e6f3ee;
                        --warn: #8a5a12;
                        --warn-soft: #fff4df;
                        --danger: #9b2c2c;
                        --ok: #1f6f5b;
                        --shadow: 0 12px 40px rgba(28, 31, 26, 0.06);
                        --radius: 16px;
                    }
                    * { box-sizing: border-box; }
                    body {
                        margin: 0;
                        color: var(--ink);
                        background:
                            radial-gradient(1200px 400px at 10% -10%, #dfece6 0%, transparent 55%),
                            linear-gradient(180deg, #eef1ea 0%, var(--bg) 40%, #eceee8 100%);
                        font-family: "IBM Plex Sans", "Segoe UI", "PingFang SC", sans-serif;
                    }
                    main { max-width: 1180px; margin: 0 auto; padding: 36px 20px 64px; }
                    .hero {
                        display: flex; justify-content: space-between; gap: 20px; align-items: end;
                        margin-bottom: 20px;
                    }
                    .brand {
                        font-size: 13px; letter-spacing: 0.16em; text-transform: uppercase;
                        color: var(--accent); font-weight: 700; margin-bottom: 8px;
                    }
                    h1 { margin: 0; font-size: 34px; letter-spacing: -0.03em; }
                    .subtitle { margin: 8px 0 0; color: var(--muted); max-width: 640px; line-height: 1.5; }
                    .pill {
                        display: inline-flex; align-items: center; gap: 8px;
                        padding: 10px 14px; border-radius: 999px; background: var(--card);
                        border: 1px solid var(--line); box-shadow: var(--shadow); font-size: 13px;
                    }
                    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
                    .card {
                        background: var(--card); border: 1px solid var(--line); border-radius: var(--radius);
                        box-shadow: var(--shadow); padding: 22px; margin-top: 16px;
                    }
                    .card h2 { margin: 0 0 6px; font-size: 18px; letter-spacing: -0.02em; }
                    .card .hint { margin: 0 0 16px; color: var(--muted); font-size: 13px; }
                    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                    .status-box {
                        border: 1px solid var(--line); border-radius: 14px; padding: 14px 16px;
                        background: linear-gradient(180deg, #fbfcf9, #fff);
                    }
                    .status-box strong { display: block; margin-bottom: 6px; }
                    .kv { font-size: 13px; color: var(--muted); line-height: 1.7; }
                    table { width: 100%; border-collapse: collapse; }
                    th, td {
                        padding: 12px 10px; border-bottom: 1px solid var(--line);
                        text-align: left; vertical-align: top; font-size: 13px;
                    }
                    th { color: var(--muted); font-weight: 600; background: #f8f9f6; }
                    code {
                        color: var(--accent); font-weight: 600;
                        font-family: "IBM Plex Mono", ui-monospace, monospace;
                    }
                    input, select {
                        width: 100%; min-width: 0; padding: 9px 11px;
                        border: 1px solid var(--line); border-radius: 10px; background: #fff;
                        font: inherit; color: var(--ink);
                    }
                    input:focus, select:focus {
                        outline: 2px solid rgba(31, 111, 91, 0.25); border-color: var(--accent);
                    }
                    button {
                        appearance: none; border: 0; border-radius: 10px; padding: 9px 14px;
                        background: var(--accent); color: #fff; cursor: pointer; font: inherit;
                    }
                    button.secondary {
                        background: #eef2ec; color: var(--ink); border: 1px solid var(--line);
                    }
                    button.danger { background: var(--danger); }
                    .badge {
                        display: inline-block; padding: 4px 8px; border-radius: 999px; font-size: 12px;
                    }
                    .hot { background: var(--accent-soft); color: var(--ok); }
                    .restart { background: var(--warn-soft); color: var(--warn); }
                    .ok { color: var(--ok); font-weight: 600; }
                    .bad { color: var(--danger); font-weight: 600; }
                    .message { margin-top: 8px; color: var(--accent); font-size: 12px; }
                    .form-grid {
                        display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px;
                    }
                    .form-actions { display: flex; gap: 8px; align-items: end; }
                    .form-actions button { white-space: nowrap; }
                    .empty {
                        padding: 18px; border-radius: 12px; background: #f7f8f5;
                        color: var(--muted); border: 1px dashed var(--line);
                    }
                    .flash {
                        margin-bottom: 12px; padding: 10px 12px; border-radius: 10px;
                        background: var(--accent-soft); color: var(--accent); font-size: 13px;
                    }
                    @media (max-width: 900px) {
                        .grid, .form-grid { grid-template-columns: 1fr; }
                        .hero { flex-direction: column; align-items: start; }
                    }
                </style>
            </head>
            <body>
                <main>
                    <section class="hero">
                        <div>
                            <div class="brand">Rover Suite</div>
                            <h1>Admin Console</h1>
                            <p class="subtitle">
                                查看实例与状态，在线管理 Gateway 路由并热生效。
                                配置归属组件自身，Admin 只做控制台。
                            </p>
                        </div>
                        <div class="pill"><span class="dot"></span>发现模式：{{DISCOVERY}}</div>
                    </section>

                    <section class="card">
                        <h2>运行状态</h2>
                        <p class="hint">每 10 秒自动刷新</p>
                        <div id="runtime-status" hx-get="/status" hx-trigger="load, every 10s">加载中...</div>
                    </section>

                    <section class="card">
                        <h2>注册实例</h2>
                        <p class="hint">来自 Nameserver 管理口</p>
                        <div id="instances" hx-get="/instances" hx-trigger="load, every 10s">加载中...</div>
                    </section>

                    <section class="card">
                        <h2>路由管理</h2>
                        <p class="hint">
                            保存后 Gateway 立刻热替换路由，并写入 <code>config/routes.overlay.json</code>。
                            当前模式：<strong>{{DISCOVERY}}</strong> —— {{MODE_HINT}}
                        </p>
                        <form class="form-grid"
                              hx-post="/routes"
                              hx-target="#routes"
                              hx-swap="innerHTML">
                            <div>
                                <label>ID</label>
                                <input name="id" placeholder="demo-api">
                            </div>
                            <div>
                                <label>businessPrefix *</label>
                                <input name="businessPrefix" placeholder="/api/demo" required>
                            </div>
                            <div>
                                <label>stripPrefix</label>
                                <input name="stripPrefix" placeholder="/api/demo">
                            </div>
                            <div>
                                <label>serviceName {{SERVICE_STAR}}</label>
                                <input name="serviceName" placeholder="demo-service" {{SERVICE_REQ}}>
                            </div>
                            <div>
                                <label>targetUrl {{TARGET_STAR}}</label>
                                <input name="targetUrl" placeholder="http://127.0.0.1:8081" {{TARGET_REQ}}>
                            </div>
                            <div>
                                <label>targetUrls（多机）</label>
                                <input name="targetUrls"
                                       placeholder="http://127.0.0.1:8081,http://127.0.0.1:8082|200">
                            </div>
                            <div>
                                <label>group</label>
                                <input name="group" placeholder="可选">
                            </div>
                            <div class="form-actions">
                                <button type="submit">保存并热更新</button>
                                <button type="reset" class="secondary">清空</button>
                            </div>
                        </form>
                        <div id="routes" hx-get="/routes" hx-trigger="load">加载中...</div>
                    </section>

                    <section class="card">
                        <h2>运行时配置</h2>
                        <p class="hint">
                            低风险项可热更新；改完立刻打到真实进程，并写入
                            <code>config/*-runtime.overlay.json</code>，重启后仍生效。
                        </p>
                        <div id="config-table" hx-get="/configs" hx-trigger="load">加载中...</div>
                    </section>
                </main>
            </body>
            </html>
            """;

    @GetMapping(value = "/status", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String status() {
        Map<String, Object> status = configService.loadStatus();
        StringBuilder html = new StringBuilder("<div class=\"grid\">");
        html.append(renderComponentStatus(castMap(status.get("gateway"))));
        html.append(renderComponentStatus(castMap(status.get("nameserver"))));
        html.append("</div><p class=\"hint\" style=\"margin-top:12px;\">刷新时间：")
                .append(escape(LocalDateTime.now().toString()))
                .append("</p>");
        return html.toString();
    }

    @GetMapping(value = "/instances", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String instances() {
        try {
            List<Map<String, Object>> rows = configService.listInstances();
            if (rows.isEmpty()) {
                return "<div class=\"empty\">当前没有注册实例</div>";
            }
            StringBuilder html = new StringBuilder("""
                    <table>
                      <thead><tr>
                        <th>服务</th><th>实例 ID</th><th>地址</th><th>分组</th><th>健康</th><th>临时</th>
                      </tr></thead><tbody>
                    """);
            for (Map<String, Object> row : rows) {
                html.append("<tr>")
                        .append("<td>").append(escape(str(row.get("serviceName")))).append("</td>")
                        .append("<td><code>").append(escape(str(row.get("instanceId")))).append("</code></td>")
                        .append("<td>").append(escape(str(row.get("host")))).append(':')
                        .append(escape(str(row.get("port")))).append("</td>")
                        .append("<td>").append(escape(str(row.get("group")))).append("</td>")
                        .append("<td>").append(boolText(row.get("healthy"))).append("</td>")
                        .append("<td>").append(escape(str(row.get("ephemeral")))).append("</td>")
                        .append("</tr>");
            }
            html.append("</tbody></table>");
            return html.toString();
        } catch (Exception ex) {
            return "<div class=\"empty bad\">" + escape(ex.getMessage()) + "</div>";
        }
    }

    @GetMapping(value = "/routes", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String routes() {
        return renderRoutes(null);
    }

    @PostMapping(
            value = "/routes",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String saveRoute(
            @RequestParam(value = "id", required = false) String id,
            @RequestParam("businessPrefix") String businessPrefix,
            @RequestParam(value = "targetUrl", required = false) String targetUrl,
            @RequestParam(value = "targetUrls", required = false) String targetUrls,
            @RequestParam(value = "serviceName", required = false) String serviceName,
            @RequestParam(value = "group", required = false) String group,
            @RequestParam(value = "stripPrefix", required = false) String stripPrefix) {
        try {
            Map<String, String> route = new LinkedHashMap<>();
            route.put("id", id == null ? "" : id);
            route.put("businessPrefix", businessPrefix);
            route.put("targetUrl", targetUrl == null ? "" : targetUrl);
            route.put("targetUrls", targetUrls == null ? "" : targetUrls);
            route.put("serviceName", serviceName == null ? "" : serviceName);
            route.put("group", group == null ? "" : group);
            route.put("stripPrefix", stripPrefix == null ? "" : stripPrefix);
            Map<String, Object> result = configService.saveRoute(route);
            String message = str(result.get("message"));
            return renderRoutes(message);
        } catch (IllegalArgumentException err) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err.getMessage(), err);
        }
    }

    @PostMapping(
            value = "/routes/delete",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String deleteRoute(@RequestParam("businessPrefix") String businessPrefix) {
        try {
            Map<String, Object> result = configService.deleteRoute(businessPrefix);
            return renderRoutes(str(result.get("message")));
        } catch (IllegalArgumentException err) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err.getMessage(), err);
        }
    }

    @GetMapping(value = "/configs", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String configs() {
        return renderConfigTable(configService.listConfigs(), "");
    }

    @PostMapping(
            value = "/configs",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String updateConfig(
            @RequestParam("component") String component,
            @RequestParam("key") String key,
            @RequestParam("value") String value) {
        try {
            ConfigUpdateResult result = configService.updateConfig(component, key, value);
            Map<String, Object> row = new LinkedHashMap<>();
            if (result.getPayload() != null) {
                row.putAll(result.getPayload());
            }
            row.put("component", component);
            row.putIfAbsent("key", key);
            row.putIfAbsent("value", value);
            row.putIfAbsent("hotReloadable", true);
            row.putIfAbsent("applyMode", "HOT_RELOAD");
            return renderConfigRow(row, result.getMessage());
        } catch (IllegalArgumentException err) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err.getMessage(), err);
        }
    }

    private String renderRoutes(String message) {
        try {
            List<Map<String, Object>> rows = configService.listRoutes();
            StringBuilder html = new StringBuilder();
            if (message != null && !message.isBlank()) {
                html.append("<div class=\"flash\">").append(escape(message)).append("</div>");
            }
            if (rows.isEmpty()) {
                html.append("<div class=\"empty\">还没有路由，先在上面表单加一条。</div>");
                return html.toString();
            }
            html.append("""
                    <table>
                      <thead><tr>
                        <th>ID</th><th>前缀</th><th>serviceName</th><th>targetUrl</th><th>targetUrls</th><th>stripPrefix</th><th></th>
                      </tr></thead><tbody>
                    """);
            for (Map<String, Object> row : rows) {
                String prefix = str(row.get("businessPrefix"));
                html.append("<tr>")
                        .append("<td>").append(escape(str(row.get("id")))).append("</td>")
                        .append("<td><code>").append(escape(prefix)).append("</code></td>")
                        .append("<td>").append(escape(str(row.get("serviceName")))).append("</td>")
                        .append("<td>").append(escape(str(row.get("targetUrl")))).append("</td>")
                        .append("<td>").append(escape(str(row.get("targetUrls")))).append("</td>")
                        .append("<td>").append(escape(str(row.get("stripPrefix")))).append("</td>")
                        .append("<td>")
                        .append("<form hx-post=\"/routes/delete\" hx-target=\"#routes\" hx-swap=\"innerHTML\" ")
                        .append("style=\"margin:0;\">")
                        .append("<input type=\"hidden\" name=\"businessPrefix\" value=\"")
                        .append(escape(prefix)).append("\">")
                        .append("<button type=\"submit\" class=\"danger\">删除</button>")
                        .append("</form></td></tr>");
            }
            html.append("</tbody></table>");
            return html.toString();
        } catch (Exception ex) {
            return "<div class=\"empty bad\">" + escape(ex.getMessage()) + "</div>";
        }
    }

    private static String renderComponentStatus(Map<String, Object> status) {
        if (status == null) {
            return "<div class=\"status-box bad\">无状态</div>";
        }
        boolean reachable = Boolean.TRUE.equals(status.get("reachable"));
        StringBuilder html = new StringBuilder("<div class=\"status-box\">");
        html.append("<strong>").append(escape(str(status.get("label")))).append("</strong> ");
        if (reachable) {
            html.append("<span class=\"ok\">在线</span><div class=\"kv\">");
            Object data = status.get("data");
            if (data instanceof JsonNode node) {
                node.fields().forEachRemaining(entry -> html.append("<div>")
                        .append(escape(entry.getKey())).append(" = ")
                        .append(escape(entry.getValue().asText()))
                        .append("</div>"));
            }
            html.append("</div>");
        } else {
            html.append("<span class=\"bad\">不可达</span>")
                    .append("<div class=\"kv bad\">").append(escape(str(status.get("error")))).append("</div>");
        }
        html.append("<div class=\"kv\" style=\"margin-top:8px;\">")
                .append(escape(str(status.get("baseUrl"))))
                .append("</div></div>");
        return html.toString();
    }

    private static String renderConfigTable(List<Map<String, Object>> items, String message) {
        if (items == null || items.isEmpty()) {
            return "<div class=\"empty\">暂无配置项</div>";
        }
        StringBuilder html = new StringBuilder("""
                <table>
                  <thead><tr>
                    <th>组件</th><th>配置项</th><th>说明</th><th>生效</th><th>当前值</th>
                  </tr></thead><tbody>
                """);
        for (Map<String, Object> item : items) {
            html.append(renderConfigRow(item, message));
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static String renderConfigRow(Map<String, Object> item, String message) {
        boolean error = Boolean.TRUE.equals(item.get("error"));
        boolean hot = Boolean.TRUE.equals(item.get("hotReloadable"))
                || ConfigApplyMode.HOT_RELOAD.name().equals(str(item.get("applyMode")));
        String messageBlock = message == null || message.isEmpty()
                ? ""
                : "<div class=\"message\">" + escape(message) + "</div>";
        if (error) {
            return """
                    <tr>
                      <td>%s</td>
                      <td><code>%s</code></td>
                      <td colspan="3" class="bad">%s</td>
                    </tr>
                    """.formatted(
                    escape(str(item.get("component"))),
                    escape(str(item.get("key"))),
                    escape(str(item.get("description"))));
        }
        return """
                <tr>
                  <td>%s</td>
                  <td><code>%s</code></td>
                  <td>%s<br><small>默认：%s</small></td>
                  <td><span class="badge %s">%s</span></td>
                  <td>
                    <form hx-post="/configs" hx-target="closest tr" hx-swap="outerHTML"
                          style="display:flex;gap:8px;align-items:center;">
                      <input type="hidden" name="component" value="%s">
                      <input type="hidden" name="key" value="%s">
                      <input type="text" name="value" value="%s">
                      <button type="submit">保存</button>
                    </form>
                    %s
                  </td>
                </tr>
                """.formatted(
                escape(str(item.get("component"))),
                escape(str(item.get("key"))),
                escape(str(item.get("description"))),
                escape(str(item.get("defaultValue"))),
                hot ? "hot" : "restart",
                hot ? "可热更新" : "需重启",
                escape(str(item.get("component"))),
                escape(str(item.get("key"))),
                escape(str(item.get("value"))),
                messageBlock);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String boolText(Object value) {
        boolean ok = Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(str(value));
        return ok ? "<span class=\"ok\">true</span>" : "<span class=\"bad\">false</span>";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
