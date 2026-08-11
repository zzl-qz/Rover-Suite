package com.rover.gateway.core.filter;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 描述 Gateway 过滤器加载配置
 *
 * 这个类是什么：过滤器链组装时的配置载体，来自 YAML 或管理端。
 * 核心职责：控制是否加载外挂 Filter、plugins 目录位置、额外指定类名列表。
 * 被谁用：GatewayRuntime、GatewayFilterAssembler 读取并决定加载哪些 Filter。
 */
@Data
public class FilterSettings {

    /** 是否加载外挂插件和配置中指定的过滤器，默认开启。 */
    private boolean enabled = true;

    /** 用户扩展 jar 目录，默认是运行目录下的 plugins。 */
    private String pluginDir = "plugins";

    /**
     * 额外按全限定类名加载的过滤器。
     * 例如：com.example.MyAuthFilter
     */
    private List<String> classes = new ArrayList<>();
}
