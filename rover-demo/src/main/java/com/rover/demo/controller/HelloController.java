package com.rover.demo.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: 最简单的业务接口，方便联调
 */
@RestController
public class HelloController {

    @Value("${spring.application.name:demo-service}")
    private String serviceName;

    @Value("${server.port:8081}")
    private int port;

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of(
                "message", "hello from rover-demo",
                "service", serviceName,
                "port", port);
    }
}
