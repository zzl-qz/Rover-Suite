package com.rover.admin;

import com.rover.admin.config.AdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 启动 Rover 轻量级网页管理端
 */
@SpringBootApplication
@EnableConfigurationProperties(AdminProperties.class)
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
