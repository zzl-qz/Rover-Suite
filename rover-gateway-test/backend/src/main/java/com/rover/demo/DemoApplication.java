package com.rover.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:10:00
 * Description: Demo 启动入口，验证 Starter 自动注册
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
