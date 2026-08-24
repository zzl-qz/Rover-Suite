# C++ HTTP Registrar 参考实现

这是一个基于 C++20、libcurl 和标准线程库的可复制参考实现，不是准备发布和长期兼容的完整 C++ SDK。它只负责服务提供方的注册、心跳和注销。

## 依赖与编译

- 支持 C++20 `std::jthread` 的编译器；
- libcurl 开发头文件和链接库；
- pthread 或平台等价线程实现。

直接编译：

```bash
c++ -std=c++20 example.cpp $(curl-config --cflags --libs) -pthread -o rover-http-example
```

或使用 CMake：

```bash
cmake -S . -B build
cmake --build build
```

实现集中在一个 header [`rover_http_registrar.hpp`](./rover_http_registrar.hpp)，复制时只需把该文件加入业务项目并链接 libcurl。

## 最小用法

```cpp
#include "rover_http_registrar.hpp"

rover::RegistrarConfig config;
config.base_url = "http://127.0.0.1:8889";
config.token = "change-me";
config.service_name = "order-service";
config.instance_id = "order-1";
config.host = "10.0.1.20";
config.port = 8080;
config.metadata = {{"version", "1.0.0"}};

// 业务 socket 监听成功后再 start。
rover::HttpRegistrar registrar{std::move(config)};
registrar.start();

// 业务进程退出前调用；析构函数也会兜底调用。
registrar.close();
```

完整的信号处理和环境变量示例见 [`example.cpp`](./example.cpp)：

```bash
ROVER_NAMESERVER_URL=http://127.0.0.1:8889 \
ROVER_NAMESERVER_TOKEN=change-me \
ROVER_SERVICE_NAME=order-service \
ROVER_INSTANCE_ID=order-1 \
ROVER_SERVICE_HOST=127.0.0.1 \
ROVER_SERVICE_PORT=8080 \
./rover-http-example
```

## 状态机与线程语义

- `start()` 创建一个 `std::jthread`，线程启动后立即注册。
- 每次 HTTP 请求完成后才开始 fixed-delay 计时，不会把慢请求叠加成并发请求。
- 默认请求超时 3 秒，注册重试、心跳间隔均为固定 5 秒；允许范围为 1～8 秒。
- 心跳固定使用本地配置间隔，不采用响应中的动态间隔；只有 `404 / INSTANCE_NOT_FOUND` 才不等待并立即完整注册。
- 网络错误、408、429 和 5xx 按固定间隔重试。
- 其他 4xx（包括 400、401、403、413 和 `409 / STALE_SESSION`）属于永久错误，进入 `FAILED`，不会让旧进程循环抢回实例。
- `close()` 请求 worker 停止并等待当前请求结束；只要调用过 `start()`，即使注册响应丢失，也会用同一 session 最多尝试一次注销，失败不重试。
- 析构函数调用 `close()`，但进程崩溃或 `SIGKILL` 时仍依赖 Nameserver TTL 摘除。

Registrar 不提供查询、订阅、本地缓存或负载均衡。若进程使用多 worker/thread，共享一个对外监听端点时也只创建一个 Registrar owner。

响应解析器只读取 Rover 自己返回的顶层 `code` 和 `message`，并要求所有 2xx 响应的 `code` 需要为 `OK`。它不是通用 JSON 库；若业务项目已经依赖 JSON 库，可在二开时替换这几个窄解析函数，不影响状态机和 HTTP 契约。
