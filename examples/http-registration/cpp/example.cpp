#include "rover_http_registrar.hpp"

#include <csignal>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

namespace {

volatile std::sig_atomic_t stop_requested = 0;

void request_stop(int) {
    stop_requested = 1;
}

std::string env_or(const char* name, std::string fallback) {
    const char* value = std::getenv(name);
    return value == nullptr || *value == '\0' ? std::move(fallback) : std::string{value};
}

int env_port(const char* name, int fallback) {
    const std::string value = env_or(name, std::to_string(fallback));
    std::size_t consumed = 0;
    const int parsed = std::stoi(value, &consumed);
    if (consumed != value.size()) {
        throw std::invalid_argument(std::string{name} + " 必须是整数");
    }
    return parsed;
}

}  // namespace

int main() {
    std::signal(SIGINT, request_stop);
    std::signal(SIGTERM, request_stop);

    try {
        const int service_port = env_port("ROVER_SERVICE_PORT", 8080);
        rover::RegistrarConfig config;
        config.base_url = env_or("ROVER_NAMESERVER_URL", "http://127.0.0.1:8889");
        config.token = env_or("ROVER_NAMESERVER_TOKEN", "");
        config.service_name = env_or("ROVER_SERVICE_NAME", "cpp-demo");
        config.instance_id = env_or("ROVER_INSTANCE_ID", "cpp-demo:" + std::to_string(service_port));
        config.host = env_or("ROVER_SERVICE_HOST", "127.0.0.1");
        config.port = service_port;
        config.metadata = {{"language", "cpp"}};
        config.request_timeout = std::chrono::seconds{3};
        config.retry_interval = std::chrono::seconds{5};
        config.heartbeat_interval = std::chrono::seconds{5};
        config.logger = [](const std::string& message) {
            std::cerr << "[rover] " << message << '\n';
        };

        // 业务 socket 必须已经监听成功，再启动 Registrar。
        rover::HttpRegistrar registrar{std::move(config)};
        registrar.start();

        while (!stop_requested && registrar.state() != rover::RegistrarState::Failed) {
            std::this_thread::sleep_for(std::chrono::milliseconds{100});
        }
        if (registrar.state() == rover::RegistrarState::Failed) {
            std::cerr << "[rover] registrar stopped: " << registrar.last_error() << '\n';
        }
        registrar.close();
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "启动 Rover Registrar 失败: " << exception.what() << '\n';
        return 1;
    }
}
