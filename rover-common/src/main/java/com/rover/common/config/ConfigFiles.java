package com.rover.common.config;

import java.nio.file.Path;

/** Rover 默认配置文件名与持久化路径。 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    public static final String DIRECTORY = "config";
    public static final String GATEWAY_YAML = "rover-gateway.yml";
    public static final String NAMESERVER_YAML = "rover-nameserver.yml";

    public static final Path GATEWAY_RUNTIME_OVERLAY =
            Path.of(DIRECTORY, "gateway-runtime.overlay.json");
    public static final Path NAMESERVER_RUNTIME_OVERLAY =
            Path.of(DIRECTORY, "nameserver-runtime.overlay.json");
    public static final Path ROUTES_OVERLAY = Path.of(DIRECTORY, "routes.overlay.json");
}
