/**
 * 作者：Daylight
 * 创建时间：2026-08-08 11:37:00
 * 描述：提供 HTMX 配置管理页面和局部刷新接口
 */
package com.rover.admin.web;

import com.rover.admin.service.AdminConfigService;
import com.rover.admin.service.ConfigUpdateResult;
import com.rover.common.config.ConfigApplyMode;
import com.rover.common.config.ConfigItem;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

@Controller
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
        return """
                <!doctype html>
                <html lang="zh-CN" x-data="{ showHelp: true }">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Rover Admin</title>
                    <script src="https://unpkg.com/htmx.org@1.9.12"></script>
                    <script defer src="https://unpkg.com/alpinejs@3.14.1/dist/cdn.min.js"></script>
                    <style>
                        body {
                            margin: 0;
                            background: #f6f7fb;
                            color: #1f2937;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                        }
                        main {
                            max-width: 1120px;
                            margin: 0 auto;
                            padding: 32px 20px;
                        }
                        header {
                            margin-bottom: 24px;
                        }
                        h1 {
                            margin: 0 0 8px;
                            font-size: 28px;
                        }
                        .card {
                            background: #ffffff;
                            border: 1px solid #e5e7eb;
                            border-radius: 14px;
                            box-shadow: 0 10px 30px rgba(15, 23, 42, 0.06);
                            padding: 20px;
                        }
                        .help {
                            margin: 16px 0;
                            padding: 12px 14px;
                            background: #eef6ff;
                            border: 1px solid #bfdbfe;
                            border-radius: 10px;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                        }
                        th,
                        td {
                            padding: 12px;
                            border-bottom: 1px solid #e5e7eb;
                            text-align: left;
                            vertical-align: top;
                        }
                        th {
                            background: #f9fafb;
                            font-weight: 600;
                        }
                        code {
                            color: #0f766e;
                            font-weight: 600;
                        }
                        input {
                            min-width: 260px;
                            padding: 8px 10px;
                            border: 1px solid #d1d5db;
                            border-radius: 8px;
                        }
                        button {
                            padding: 8px 12px;
                            border: 0;
                            border-radius: 8px;
                            background: #2563eb;
                            color: #ffffff;
                            cursor: pointer;
                        }
                        .badge {
                            display: inline-block;
                            padding: 4px 8px;
                            border-radius: 999px;
                            font-size: 12px;
                        }
                        .hot {
                            background: #dcfce7;
                            color: #166534;
                        }
                        .restart {
                            background: #fef3c7;
                            color: #92400e;
                        }
                        .message {
                            margin-top: 6px;
                            color: #2563eb;
                            font-size: 12px;
                        }
                    </style>
                </head>
                <body>
                    <main>
                        <header>
                            <h1>Rover Admin</h1>
                            <p>轻量级配置管理页面，支持部分配置热更新。</p>
                            <button type="button" x-on:click="showHelp = !showHelp">显示/隐藏说明</button>
                        </header>
                        <section class="help" x-show="showHelp">
                            配置归属于 Gateway 和 Nameserver，Admin 只负责展示和提交管理操作。
                        </section>
                        <section class="card">
                            <h2>运行状态</h2>
                            <div id="runtime-status" hx-get="/status" hx-trigger="load, every 10s">
                                正在加载运行状态...
                            </div>
                        </section>
                        <section class="card" style="margin-top: 16px;">
                            <h2>配置管理</h2>
                            <div id="config-table" hx-get="/configs" hx-trigger="load" hx-swap="innerHTML">
                                正在加载配置...
                            </div>
                        </section>
                    </main>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/configs", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String configs() {
        return renderTable(configService.listConfigs());
    }

    @GetMapping(value = "/status", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String status() {
        int configCount = configService.listConfigs().size();
        return """
                <p>Gateway / Nameserver 配置管理器已接入。</p>
                <p>当前配置项数量：%d</p>
                <p>状态刷新时间：%s</p>
                """.formatted(configCount, escape(LocalDateTime.now().toString()));
    }

    @PostMapping(
            value = "/configs",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String updateConfig(@RequestParam String key, @RequestParam String value) {
        try {
            ConfigUpdateResult result = configService.updateConfig(key, value);
            return renderRow(result.getItem(), result.getMessage());
        } catch (IllegalArgumentException err) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err.getMessage(), err);
        } catch (UnsupportedOperationException err) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, err.getMessage(), err);
        }
    }

    private static String renderTable(List<ConfigItem> items) {
        if (items.isEmpty()) {
            return """
                    <div class="help">
                        Gateway / Nameserver 的管理接口尚未接入。
                        当前页面只保留控制台骨架，不在 Admin 本地维护真实配置。
                    </div>
                    """;
        }

        StringBuilder html = new StringBuilder();
        html.append("""
                <table>
                    <thead>
                        <tr>
                            <th>配置项</th>
                            <th>说明</th>
                            <th>生效方式</th>
                            <th>当前值 / 操作</th>
                        </tr>
                    </thead>
                    <tbody>
                """);
        for (ConfigItem item : items) {
            html.append(renderRow(item, ""));
        }
        html.append("""
                    </tbody>
                </table>
                """);
        return html.toString();
    }

    private static String renderRow(ConfigItem item, String message) {
        String inputType = item.isSensitive() ? "password" : "text";
        String badgeClass = item.isHotReloadable() ? "hot" : "restart";
        String messageBlock = message.isEmpty()
                ? ""
                : "<div class=\"message\">" + escape(message) + "</div>";

        return """
                <tr>
                    <td><code>%s</code></td>
                    <td>%s<br><small>默认值：%s</small></td>
                    <td><span class="badge %s">%s</span></td>
                    <td>
                        <form hx-post="/configs" hx-target="closest tr" hx-swap="outerHTML">
                            <input type="hidden" name="key" value="%s">
                            <input type="%s" name="value" value="%s">
                            <button type="submit">保存</button>
                            %s
                        </form>
                    </td>
                </tr>
                """.formatted(
                escape(item.getKey()),
                escape(item.getDescription()),
                escape(item.getDefaultValue()),
                badgeClass,
                renderApplyMode(item.getApplyMode()),
                escape(item.getKey()),
                inputType,
                escape(item.getValue()),
                messageBlock);
    }

    private static String renderApplyMode(ConfigApplyMode applyMode) {
        if (ConfigApplyMode.HOT_RELOAD.equals(applyMode)) {
            return "可热更新";
        }
        return "需重启";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return HtmlUtils.htmlEscape(value);
    }
}
