package com.rover.admin;

import com.rover.admin.config.AdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Author: Daylight
 * Created: 2026-08-12 10:30:00
 * Description: Rover 管理端启动入口
 */
@SpringBootApplication
@EnableConfigurationProperties(AdminProperties.class)
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
