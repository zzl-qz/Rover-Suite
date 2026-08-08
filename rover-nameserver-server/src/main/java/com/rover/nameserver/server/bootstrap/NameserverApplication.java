/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：启动 Rover nameserver 服务
 */
package com.rover.nameserver.server.bootstrap;

import com.rover.nameserver.server.bootstrap.config.NameserverConfig;
import com.rover.nameserver.server.bootstrap.config.NameserverConfigLoader;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NameserverApplication {

    public static void main(String[] args) {
        NameserverConfig config = new NameserverConfigLoader().load();
        log.info("Rover Nameserver starting on port {}...", config.getPortOrDefault());
    }
}
