# Rover HTTP Registrar 参考实现

Nameserver 开关、Java Starter 对比、鉴权、恢复与多 worker 总体说明见公开
[服务注册指南](../../docs-public/service-registration.zh-CN.md)；设计权衡见
[架构说明](../../docs-public/architecture.zh-CN.md)。

这里提供 Node.js、Python、Go、PHP 与 C++ 的轻量参考实现。它们不是完整 SDK，
只负责一个业务实例的 `register → heartbeat → unregister` 生命周期，可以直接复制进项目后按需二开。

Node.js、Python 和 Go 使用各自标准库；PHP 使用运行环境常见的 cURL 扩展；C++ 使用 libcurl。
没有任何实现会引入 Agent、Sidecar 或新的 Rover 常驻组件。

Nameserver 的 HTTP Registration API（内部配置名称为 Client API）默认关闭。接入前需要在服务端显式设置
`clientApiEnabled: true`。token 可以保持为空以便在本地或可信网络零配置接入；有访问控制要求时，
再配置非空 `rover.nameserver.token`，并按部署环境限制 HTTP 端口来源。

## 共同语义

- 必须在业务端口已经 `listen/ready` 后调用 `start()`。
- 构造 Registrar 时自动生成本次进程生命周期唯一的 UUID `sessionId`。
- 启动后立即完整注册；失败后固定等待 5 秒重试，不做指数退避。
- 注册成功后使用 fixed-delay 每 5 秒心跳；单请求超时配置默认为 3 秒。
- 任意时刻最多一个 HTTP 请求在途，不会堆积心跳或注册请求。
- 只有 `2xx` 且 JSON `code=OK` 才视为成功；无效成功响应按暂态错误重试。
- 只有心跳同时收到 HTTP `404` 且响应 `code` 精确为 `INSTANCE_NOT_FOUND` 时，才立即完整注册。
- 注册请求的任何 `404`，以及心跳的 `404 NOT_FOUND`（例如 API 未启用或路径错误）都属于永久错误，不能循环抢注。
- 网络错误、`408`、`429`、`5xx` 固定等待 5 秒后重试当前操作。
- `400/401/403/404/405/409/413/415`（排除上述心跳 `INSTANCE_NOT_FOUND` 特例）属于永久错误，当前 Registrar 进入 `FAILED` 并停止重试。
- 关闭时先停止调度、等待在途请求，然后最多尽力注销一次；注销失败不阻止退出。
- token 通过 `Authorization: Bearer <token>` 发送。

默认参数已经固定为重试 5 秒、心跳 5 秒、超时 3 秒。构造参数允许覆盖时间主要是为了本地测试；
生产环境建议保持默认值；可配置实现会拒绝超过 8 秒的重试或心跳间隔。

## Node.js

文件：[node/rover_registrar.js](node/rover_registrar.js)

```js
const http = require('node:http');
const { RoverRegistrar } = require('./rover_registrar');

const server = http.createServer((_request, response) => {
  response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  response.end('order-service is ready');
});
const registrar = new RoverRegistrar({
  nameserverUrl: 'http://127.0.0.1:8889',
  token: process.env.ROVER_NAMESERVER_TOKEN,
  serviceName: 'order-service',
  instanceId: process.env.POD_UID || `${process.env.HOSTNAME || 'local'}-8080`,
  host: process.env.POD_IP || '127.0.0.1',
  port: 8080,
  metadata: { version: 'v1' },
});

server.listen(8080, '0.0.0.0', () => registrar.start());

async function shutdown() {
  await registrar.close();
  server.close(() => process.exit(0));
}
process.once('SIGTERM', shutdown);
process.once('SIGINT', shutdown);
```

运行测试：

```bash
node --test examples/http-registration/node/rover_registrar.test.js
```

## Python

文件：[python/rover_registrar.py](python/rover_registrar.py)

```python
import os
from rover_registrar import RoverRegistrar

registrar = RoverRegistrar(
    nameserver_url="http://127.0.0.1:8889",
    token=os.getenv("ROVER_NAMESERVER_TOKEN", ""),
    service_name="order-service",
    instance_id=os.getenv("POD_UID", os.getenv("HOSTNAME", "local") + "-8080"),
    host=os.getenv("POD_IP", "127.0.0.1"),
    port=8080,
    metadata={"version": "v1"},
)

# 在 ASGI lifespan、框架 ready 回调或监听端口成功之后调用。
registrar.start()

# 在框架 shutdown/lifespan 退出阶段调用。
registrar.close()
```

运行测试：

```bash
python3 -m unittest discover -s examples/http-registration/python -p 'test_*.py'
```

## Go

文件：[go/registrar.go](go/registrar.go)

```go
instanceID := os.Getenv("POD_UID")
if instanceID == "" {
    instanceID = "local-8080"
}
host := os.Getenv("POD_IP")
if host == "" {
    host = "127.0.0.1"
}

r, err := registrar.New(registrar.Config{
    NameserverURL: "http://127.0.0.1:8889",
    Token:         os.Getenv("ROVER_NAMESERVER_TOKEN"),
    ServiceName:   "order-service",
    InstanceID:    instanceID,
    Host:          host,
    Port:          8080,
})
if err != nil {
    return err
}
if err := r.Start(); err != nil {
    return err
}
defer r.Close()
```

详细接入说明见 [go/README.md](go/README.md)。运行测试：

```bash
cd examples/http-registration/go
go test ./...
```

## PHP

文件：[php/RoverHttpRegistrar.php](php/RoverHttpRegistrar.php)

```php
$registrar = new RoverHttpRegistrar([
    'baseUrl' => 'http://127.0.0.1:8889',
    'token' => getenv('ROVER_NAMESERVER_TOKEN') ?: '',
    'serviceName' => 'order-service',
    'instanceId' => getenv('POD_UID') ?: gethostname() . ':8080',
    'host' => getenv('POD_IP') ?: '127.0.0.1',
    'port' => 8080,
]);

$stop = false;
if (function_exists('pcntl_async_signals') && function_exists('pcntl_signal')) {
    pcntl_async_signals(true);
    pcntl_signal(SIGTERM, static function () use (&$stop): void { $stop = true; });
    pcntl_signal(SIGINT, static function () use (&$stop): void { $stop = true; });
}
$registrar->run(static function () use (&$stop): bool { return $stop; });
```

PHP 只支持 CLI、Swoole、RoadRunner、Octane 等长驻进程，**不支持普通 PHP-FPM 请求生命周期**；多 worker 环境必须安排一个唯一 owner。详细说明与 CLI 示例见 [php/README.md](php/README.md)。

有 PHP 环境时可检查语法：

```bash
php -l examples/http-registration/php/RoverHttpRegistrar.php
php -l examples/http-registration/php/example.php
```

## C++

文件：[cpp/rover_http_registrar.hpp](cpp/rover_http_registrar.hpp)

```cpp
rover::RegistrarConfig config;
config.base_url = "http://127.0.0.1:8889";
config.token = "change-me";
config.service_name = "order-service";
config.instance_id = "order-1";
config.host = "10.0.1.20";
config.port = 8080;

rover::HttpRegistrar registrar{std::move(config)};
registrar.start();
// 业务退出前：
registrar.close();
```

C++ 版本使用 C++20 `std::jthread` 串行执行请求，以 RAII 析构兜底关闭。详细说明、CMake 和可运行示例见 [cpp/README.md](cpp/README.md)。直接编译：

```bash
c++ -std=c++20 examples/http-registration/cpp/example.cpp \
  $(curl-config --cflags --libs) -pthread -o rover-http-example
```

## 多 worker 的唯一 owner 约束

注册 owner 表示一个对外监听端点或 Pod，而不是框架内部的每个 worker。对于 Node cluster、
Gunicorn、uWSGI、RoadRunner、Octane 等共享同一监听端口的多 worker 模式，只能由 master、容器生命周期或其他
唯一协调者启动一个 Registrar。不要让多个 worker 使用相同 `serviceName + instanceId` 分别注册：
后启动的 session 会接管实例，旧 worker 随后的心跳会收到 `STALE_SESSION`，旧 worker 注销也会被拒绝。

只有当每个 worker 确实拥有独立、可直接访问的端口和唯一 `instanceId` 时，才应分别启动 Registrar。
