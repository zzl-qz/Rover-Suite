#pragma once

#include <curl/curl.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <iomanip>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <utility>

namespace rover {

struct RegistrarConfig {
    std::string base_url;
    std::string token;
    std::string service_name;
    std::string instance_id;
    std::string host;
    int port{0};
    int weight{100};
    std::optional<std::string> group;
    std::optional<std::string> zone;
    std::map<std::string, std::string> metadata;
    std::chrono::milliseconds request_timeout{3'000};
    std::chrono::milliseconds retry_interval{5'000};
    std::chrono::milliseconds heartbeat_interval{5'000};
    std::function<void(const std::string&)> logger;
};

enum class RegistrarState {
    New,
    Registering,
    Registered,
    Failed,
    Stopping,
    Closed,
};

inline std::string_view state_name(RegistrarState state) noexcept {
    switch (state) {
        case RegistrarState::New:
            return "NEW";
        case RegistrarState::Registering:
            return "REGISTERING";
        case RegistrarState::Registered:
            return "REGISTERED";
        case RegistrarState::Failed:
            return "FAILED";
        case RegistrarState::Stopping:
            return "STOPPING";
        case RegistrarState::Closed:
            return "CLOSED";
    }
    return "UNKNOWN";
}

/**
 * Rover HTTP Registration API 的 header-only 参考实现。
 *
 * 一个 worker 线程串行执行注册/心跳；close() 先停止并 join worker，再尽力注销一次，
 * 因而同一 Registrar 始终只有一个 HTTP 请求在途。
 */
class HttpRegistrar final {
public:
    explicit HttpRegistrar(RegistrarConfig config)
        : config_(std::move(config)),
          session_id_(uuid_v4()) {
        initialize_curl();
        normalize_and_validate_config();
    }

    HttpRegistrar(const HttpRegistrar&) = delete;
    HttpRegistrar& operator=(const HttpRegistrar&) = delete;
    HttpRegistrar(HttpRegistrar&&) = delete;
    HttpRegistrar& operator=(HttpRegistrar&&) = delete;

    ~HttpRegistrar() noexcept {
        try {
            close();
        } catch (...) {
            // 析构阶段不向业务退出链路传播异常；TTL 仍会兜底摘除。
        }
    }

    /** 创建唯一 worker；worker 启动后不等待间隔，立即执行第一次注册。 */
    void start() {
        std::lock_guard lock(mutex_);
        if (closed_) {
            throw std::logic_error("Registrar 已关闭，不能重新启动");
        }
        if (state_ != RegistrarState::New) {
            return;
        }
        started_ = true;
        state_ = RegistrarState::Registering;
        worker_ = std::jthread([this](std::stop_token stop_token) { run(stop_token); });
    }

    /** 停止 worker；只要 start 过就用同一 session 最多尝试一次注销。可重复调用。 */
    void close() {
        {
            std::unique_lock lock(mutex_);
            if (closed_) {
                return;
            }
            if (closing_) {
                close_cv_.wait(lock, [this] { return !closing_; });
                return;
            }
            closing_ = true;
            state_ = RegistrarState::Stopping;
            if (worker_.joinable()) {
                worker_.request_stop();
            }
            wait_cv_.notify_all();

            // logger 回调在 worker 上执行；即使回调误调用 close()，也不能 join 自己。
            if (worker_.joinable() && worker_.get_id() == std::this_thread::get_id()) {
                closing_ = false;
                close_cv_.notify_all();
                return;
            }
        }

        if (worker_.joinable()) {
            worker_.join();
        }

        bool should_unregister = false;
        {
            std::lock_guard lock(mutex_);
            // 注册响应可能在服务端写入后丢失。只要 start() 已调用，就用同一
            // session best-effort 注销一次，不能仅依赖是否收到了成功响应。
            should_unregister = started_;
        }
        if (should_unregister) {
            // best-effort：不重试、不让注销失败阻止进程退出。
            (void)perform_request(Operation::Unregister);
        }

        {
            std::lock_guard lock(mutex_);
            closed_ = true;
            closing_ = false;
            state_ = RegistrarState::Closed;
        }
        close_cv_.notify_all();
    }

    [[nodiscard]] RegistrarState state() const {
        std::lock_guard lock(mutex_);
        return state_;
    }

    [[nodiscard]] std::string last_error() const {
        std::lock_guard lock(mutex_);
        return last_error_;
    }

    [[nodiscard]] const std::string& session_id() const noexcept {
        return session_id_;
    }

private:
    static constexpr std::string_view kRegisterPath = "/v1/client/instances/register";
    static constexpr std::string_view kHeartbeatPath = "/v1/client/instances/heartbeat";
    static constexpr std::string_view kUnregisterPath = "/v1/client/instances/unregister";
    static constexpr auto kMinInterval = std::chrono::milliseconds{1'000};
    static constexpr auto kMaxInterval = std::chrono::milliseconds{8'000};
    static constexpr std::size_t kMaxResponseBytes = 64U * 1024U;

    enum class Operation { Register, Heartbeat, Unregister };

    struct HttpResult {
        long status{0};
        std::string body;
        std::string network_error;
    };

    struct NextAction {
        Operation operation{Operation::Register};
        std::chrono::milliseconds delay{0};
        bool stop{false};
    };

    struct CurlDeleter {
        void operator()(CURL* handle) const noexcept {
            if (handle != nullptr) {
                curl_easy_cleanup(handle);
            }
        }
    };

    struct HeaderList {
        curl_slist* value{nullptr};

        HeaderList() = default;
        HeaderList(const HeaderList&) = delete;
        HeaderList& operator=(const HeaderList&) = delete;

        ~HeaderList() {
            if (value != nullptr) {
                curl_slist_free_all(value);
            }
        }

        void append(const std::string& header) {
            curl_slist* next = curl_slist_append(value, header.c_str());
            if (next == nullptr) {
                throw std::runtime_error("构造 HTTP header 失败");
            }
            value = next;
        }
    };

    struct CurlGlobal final {
        CurlGlobal() {
            const CURLcode code = curl_global_init(CURL_GLOBAL_DEFAULT);
            if (code != CURLE_OK) {
                throw std::runtime_error(std::string{"curl_global_init 失败: "} + curl_easy_strerror(code));
            }
        }
        ~CurlGlobal() { curl_global_cleanup(); }
    };

    RegistrarConfig config_;
    const std::string session_id_;
    mutable std::mutex mutex_;
    std::condition_variable wait_cv_;
    std::condition_variable close_cv_;
    std::jthread worker_;
    RegistrarState state_{RegistrarState::New};
    bool started_{false};
    bool closing_{false};
    bool closed_{false};
    std::string last_error_;

    static void initialize_curl() {
        static const CurlGlobal global;
        (void)global;
    }

    void normalize_and_validate_config() {
        while (config_.base_url.size() > 1 && config_.base_url.back() == '/') {
            config_.base_url.pop_back();
        }
        if (!(config_.base_url.starts_with("http://") || config_.base_url.starts_with("https://"))) {
            throw std::invalid_argument("base_url 必须以 http:// 或 https:// 开头");
        }
        const std::size_t authority_start = config_.base_url.find("://") + 3;
        const std::size_t authority_end = config_.base_url.find('/', authority_start);
        if (authority_start >= config_.base_url.size()
            || authority_end == authority_start) {
            throw std::invalid_argument("base_url 必须包含有效 host");
        }
        require_non_blank(config_.service_name, "service_name");
        require_non_blank(config_.instance_id, "instance_id");
        require_non_blank(config_.host, "host");
        if (config_.port < 1 || config_.port > 65'535) {
            throw std::invalid_argument("port 必须在 1-65535 之间");
        }
        if (config_.weight <= 0) {
            throw std::invalid_argument("weight 必须大于 0");
        }
        require_interval(config_.retry_interval, "retry_interval");
        require_interval(config_.heartbeat_interval, "heartbeat_interval");
        if (config_.request_timeout <= std::chrono::milliseconds{0}) {
            throw std::invalid_argument("request_timeout 必须大于 0");
        }
        if (config_.token.find_first_of("\r\n") != std::string::npos) {
            throw std::invalid_argument("token 不能包含换行符");
        }
        for (const auto& [key, value] : config_.metadata) {
            require_non_blank(key, "metadata key");
            (void)value;
        }
    }

    static void require_non_blank(const std::string& value, std::string_view name) {
        if (value.empty() || std::all_of(value.begin(), value.end(), [](unsigned char ch) {
                return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
            })) {
            throw std::invalid_argument(std::string{name} + " 不能为空");
        }
    }

    static void require_interval(std::chrono::milliseconds value, std::string_view name) {
        if (value < kMinInterval || value > kMaxInterval) {
            throw std::invalid_argument(
                    std::string{name} + " 必须在 1000-8000ms 之间");
        }
    }

    void run(std::stop_token stop_token) noexcept {
        try {
            Operation operation = Operation::Register;
            while (!stop_token.stop_requested()) {
                HttpResult result = perform_request(operation);
                NextAction next = operation == Operation::Register
                        ? handle_register(result)
                        : handle_heartbeat(result);
                if (next.stop) {
                    return;
                }
                operation = next.operation;
                if (next.delay <= std::chrono::milliseconds{0}) {
                    continue;
                }

                std::unique_lock lock(mutex_);
                wait_cv_.wait_for(lock, next.delay, [&stop_token] {
                    return stop_token.stop_requested();
                });
            }
        } catch (const std::exception& exception) {
            set_failed(std::string{"Registrar worker 异常: "} + exception.what());
        } catch (...) {
            set_failed("Registrar worker 发生未知异常");
        }
    }

    NextAction handle_register(const HttpResult& result) {
        if (is_http_success(result)) {
            if (!has_ok_code(result.body)) {
                return schedule_transient(
                        Operation::Register, "注册响应不是有效的 Rover JSON");
            }
            {
                std::lock_guard lock(mutex_);
                state_ = RegistrarState::Registered;
                last_error_.clear();
            }
            log("注册成功，进入 REGISTERED");
            return {Operation::Heartbeat, config_.heartbeat_interval, false};
        }
        if (is_transient(result)) {
            return schedule_transient(Operation::Register, failure_description("注册", result));
        }
        set_failed(failure_description("注册", result));
        return {.stop = true};
    }

    NextAction handle_heartbeat(const HttpResult& result) {
        if (is_http_success(result)) {
            if (!has_ok_code(result.body)) {
                return schedule_transient(
                        Operation::Heartbeat, "心跳响应不是有效的 Rover JSON");
            }
            {
                std::lock_guard lock(mutex_);
                last_error_.clear();
            }
            return {Operation::Heartbeat, config_.heartbeat_interval, false};
        }

        const auto code = json_string_field(result.body, "code");
        if (result.status == 404 && code == std::optional<std::string>{"INSTANCE_NOT_FOUND"}) {
            {
                std::lock_guard lock(mutex_);
                state_ = RegistrarState::Registering;
            }
            log("心跳发现实例不存在，立即提交完整注册信息");
            return {Operation::Register, std::chrono::milliseconds{0}, false};
        }
        if (is_transient(result)) {
            return schedule_transient(Operation::Heartbeat, failure_description("心跳", result));
        }
        set_failed(failure_description("心跳", result));
        return {.stop = true};
    }

    NextAction schedule_transient(Operation operation, std::string message) {
        {
            std::lock_guard lock(mutex_);
            last_error_ = message;
            state_ = operation == Operation::Register
                    ? RegistrarState::Registering
                    : RegistrarState::Registered;
        }
        log(message + "；固定 " + std::to_string(config_.retry_interval.count()) + "ms 后重试");
        return {operation, config_.retry_interval, false};
    }

    void set_failed(std::string message) {
        {
            std::lock_guard lock(mutex_);
            state_ = RegistrarState::Failed;
            last_error_ = message;
        }
        log(message + "；属于永久错误，Registrar 已停止");
    }

    HttpResult perform_request(Operation operation) const {
        std::unique_ptr<CURL, CurlDeleter> curl{curl_easy_init()};
        if (!curl) {
            return {.network_error = "curl_easy_init 失败"};
        }

        const std::string path = operation == Operation::Register
                ? std::string{kRegisterPath}
                : operation == Operation::Heartbeat
                        ? std::string{kHeartbeatPath}
                        : std::string{kUnregisterPath};
        const std::string url = config_.base_url + path;
        const std::string request_body = operation == Operation::Register
                ? registration_json()
                : session_json();

        HeaderList headers;
        headers.append("Content-Type: application/json");
        headers.append("Accept: application/json");
        if (!config_.token.empty()) {
            headers.append("Authorization: Bearer " + config_.token);
        }

        HttpResult result;
        curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
        curl_easy_setopt(curl.get(), CURLOPT_POST, 1L);
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDS, request_body.c_str());
        curl_easy_setopt(curl.get(), CURLOPT_POSTFIELDSIZE, static_cast<long>(request_body.size()));
        curl_easy_setopt(curl.get(), CURLOPT_HTTPHEADER, headers.value);
        curl_easy_setopt(curl.get(), CURLOPT_CONNECTTIMEOUT_MS,
                         static_cast<long>(config_.request_timeout.count()));
        curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT_MS,
                         static_cast<long>(config_.request_timeout.count()));
        curl_easy_setopt(curl.get(), CURLOPT_NOSIGNAL, 1L);
        curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, &HttpRegistrar::write_response);
        curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &result.body);

        const CURLcode code = curl_easy_perform(curl.get());
        if (code != CURLE_OK) {
            result.network_error = curl_easy_strerror(code);
            return result;
        }
        curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &result.status);
        return result;
    }

    [[nodiscard]] std::string registration_json() const {
        std::ostringstream out;
        out << '{'
            << "\"serviceName\":\"" << json_escape(config_.service_name) << "\","
            << "\"instanceId\":\"" << json_escape(config_.instance_id) << "\","
            << "\"sessionId\":\"" << session_id_ << "\","
            << "\"host\":\"" << json_escape(config_.host) << "\","
            << "\"port\":" << config_.port << ','
            << "\"weight\":" << config_.weight;
        if (config_.group.has_value()) {
            out << ",\"group\":\"" << json_escape(*config_.group) << '"';
        }
        if (config_.zone.has_value()) {
            out << ",\"zone\":\"" << json_escape(*config_.zone) << '"';
        }
        out << ",\"metadata\":{";
        bool first = true;
        for (const auto& [key, value] : config_.metadata) {
            if (!first) {
                out << ',';
            }
            first = false;
            out << '"' << json_escape(key) << "\":\"" << json_escape(value) << '"';
        }
        out << "}}";
        return out.str();
    }

    [[nodiscard]] std::string session_json() const {
        std::ostringstream out;
        out << '{'
            << "\"serviceName\":\"" << json_escape(config_.service_name) << "\","
            << "\"instanceId\":\"" << json_escape(config_.instance_id) << "\","
            << "\"sessionId\":\"" << session_id_ << "\"}";
        return out.str();
    }

    static bool is_http_success(const HttpResult& result) noexcept {
        return result.network_error.empty() && result.status >= 200 && result.status < 300;
    }

    static bool is_transient(const HttpResult& result) noexcept {
        if (!result.network_error.empty() || result.status == 0) {
            return true;
        }
        return result.status == 408
                || result.status == 429
                || (result.status >= 500 && result.status <= 599);
    }

    static bool has_ok_code(const std::string& body) {
        const auto code = json_string_field(body, "code");
        return code == std::optional<std::string>{"OK"};
    }

    static std::string failure_description(std::string_view operation, const HttpResult& result) {
        if (!result.network_error.empty()) {
            return std::string{operation} + "网络错误: " + result.network_error;
        }
        std::string description = std::string{operation} + "失败: HTTP " + std::to_string(result.status);
        if (auto code = json_string_field(result.body, "code"); code.has_value()) {
            description += ", code=" + *code;
        }
        if (auto message = json_string_field(result.body, "message"); message.has_value()) {
            description += ", message=" + *message;
        }
        return description;
    }

    void log(const std::string& message) const noexcept {
        if (!config_.logger) {
            return;
        }
        try {
            config_.logger(message);
        } catch (...) {
            // 日志回调不能破坏注册状态机。
        }
    }

    static std::size_t write_response(
            char* data, std::size_t size, std::size_t count, void* target) noexcept {
        const std::size_t bytes = size * count;
        try {
            auto* response = static_cast<std::string*>(target);
            if (bytes > kMaxResponseBytes - response->size()) {
                return 0;
            }
            response->append(data, bytes);
            return bytes;
        } catch (...) {
            return 0;
        }
    }

    static std::string json_escape(std::string_view value) {
        std::ostringstream out;
        out << std::hex << std::setfill('0');
        for (const unsigned char ch : value) {
            switch (ch) {
                case '"':
                    out << "\\\"";
                    break;
                case '\\':
                    out << "\\\\";
                    break;
                case '\b':
                    out << "\\b";
                    break;
                case '\f':
                    out << "\\f";
                    break;
                case '\n':
                    out << "\\n";
                    break;
                case '\r':
                    out << "\\r";
                    break;
                case '\t':
                    out << "\\t";
                    break;
                default:
                    if (ch < 0x20) {
                        out << "\\u" << std::setw(4) << static_cast<int>(ch);
                    } else {
                        out << static_cast<char>(ch);
                    }
            }
        }
        return out.str();
    }

    static std::optional<std::size_t> json_field_value_position(
            const std::string& json, std::string_view key) {
        const std::string needle = "\"" + std::string{key} + "\"";
        std::size_t position = json.find(needle);
        if (position == std::string::npos) {
            return std::nullopt;
        }
        position = json.find(':', position + needle.size());
        if (position == std::string::npos) {
            return std::nullopt;
        }
        ++position;
        while (position < json.size()
               && (json[position] == ' ' || json[position] == '\t'
                   || json[position] == '\r' || json[position] == '\n')) {
            ++position;
        }
        return position;
    }

    static std::optional<std::string> json_string_field(
            const std::string& json, std::string_view key) {
        auto position = json_field_value_position(json, key);
        if (!position.has_value() || *position >= json.size() || json[*position] != '"') {
            return std::nullopt;
        }
        std::string value;
        bool escaped = false;
        for (std::size_t index = *position + 1; index < json.size(); ++index) {
            const char ch = json[index];
            if (escaped) {
                switch (ch) {
                    case '"':
                    case '\\':
                    case '/':
                        value.push_back(ch);
                        break;
                    case 'b':
                        value.push_back('\b');
                        break;
                    case 'f':
                        value.push_back('\f');
                        break;
                    case 'n':
                        value.push_back('\n');
                        break;
                    case 'r':
                        value.push_back('\r');
                        break;
                    case 't':
                        value.push_back('\t');
                        break;
                    default:
                        return std::nullopt;
                }
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return value;
            } else {
                value.push_back(ch);
            }
        }
        return std::nullopt;
    }

    static std::string uuid_v4() {
        std::array<std::uint8_t, 16> bytes{};
        std::random_device random;
        for (auto& byte : bytes) {
            byte = static_cast<std::uint8_t>(random());
        }
        bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0fU) | 0x40U);
        bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3fU) | 0x80U);

        std::ostringstream out;
        out << std::hex << std::setfill('0');
        for (std::size_t index = 0; index < bytes.size(); ++index) {
            if (index == 4 || index == 6 || index == 8 || index == 10) {
                out << '-';
            }
            out << std::setw(2) << static_cast<unsigned>(bytes[index]);
        }
        return out.str();
    }
};

}  // namespace rover
