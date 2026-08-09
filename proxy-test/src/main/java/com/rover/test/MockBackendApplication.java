package com.rover.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MockBackend: Rover Gateway 代理测试工具箱。
 *
 * 一个 main 两个实例：
 *   实例1：直接 Run（默认 8081，对应网关 user-api 路由）
 *   实例2：Run 配置加参数 --server.port=8070（对应网关 test-api 路由）
 *
 * 请求链路：浏览器 -> 网关(8080) -> 本后端(8081/8070) -> 网关 -> 浏览器
 * 前端测试页由本应用静态托管：http://127.0.0.1:8081/
 *
 * 端点（网关剥离 /api 前缀后的路径，故双前缀映射）：
 *   /user/echo 或 /api/echo       回显 method/path/query/headers/body
 *   /user/status/{code}           返回指定状态码
 *   /user/slow?ms=                延迟（测超时）
 *   /user/big?kb=                 大响应体
 *   /user/cookie-set              设置测试 Cookie
 *   /user/verify                  规格自校验（期望值放 X-Verify-Spec 头）
 */
@SpringBootApplication
public class MockBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockBackendApplication.class, args);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("*")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
