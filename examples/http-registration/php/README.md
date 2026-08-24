# PHP HTTP Registrar 参考实现

这是一份可复制的 Rover 注册生命周期参考代码，不是发布到 Composer 的完整 SDK。它只做三件事：注册、心跳和注销，不包含服务查询、订阅、负载均衡或 Gateway 调用能力。

## 适用范围

仅适用于能够持续运行并持有定时任务的进程：

- CLI 常驻进程；
- Swoole；
- RoadRunner；
- Laravel Octane；
- 其他有明确启动、定时调度和关闭生命周期的常驻运行时。

**普通 PHP-FPM 请求生命周期不支持。** 避免在 Controller、Middleware 或每次请求的 bootstrap 中创建 Registrar。请求结束后定时器随即消失，多 worker 还会争用同一个实例。若系统只能使用 PHP-FPM，应让容器级长驻进程或部署生命周期承担注册，但这已经超出本参考实现的范围。

RoadRunner、Octane 等多 worker 环境也只能启动一个 Registrar owner。注册单位是一个对外监听端点/Pod，不是每个 PHP worker。

## 依赖

- PHP 7.4 或更高版本；
- PHP cURL 扩展；
- 可选 `pcntl`，仅用于示例捕获 `SIGINT/SIGTERM`。

检查语法：

```bash
php -l RoverHttpRegistrar.php
php -l example.php
```

## 最小用法

业务端口监听成功后创建 Registrar：

```php
require __DIR__ . '/RoverHttpRegistrar.php';

$registrar = new RoverHttpRegistrar([
    'baseUrl' => 'http://127.0.0.1:8889',
    'token' => 'change-me',
    'serviceName' => 'order-service',
    'instanceId' => getenv('POD_UID') ?: gethostname() . ':8080',
    'host' => '10.0.1.20',
    'port' => 8080,
    'metadata' => ['version' => '1.0.0'],
]);

// 独立 CLI，或者框架提供的唯一长驻协程/任务。
$registrar->run(static fn(): bool => false);
```

`run()` 是阻塞循环。在 Swoole 中，应把它放进唯一协程，并开启支持 cURL 的 runtime hook；在已有事件循环中，也可以调用 `start()`，然后每 50～100ms 调一次非阻塞调度入口 `tick()`，退出前调用 `close()`：

```php
$registrar->start();       // 立即尝试注册
$registrar->tick();        // 由长驻事件循环周期调用
$registrar->close();       // 最多尝试一次注销，不阻塞退出做重试
```

完整的 CLI 信号处理示例见 [`example.php`](./example.php)。可通过以下环境变量运行：

```bash
ROVER_NAMESERVER_URL=http://127.0.0.1:8889 \
ROVER_NAMESERVER_TOKEN=change-me \
ROVER_SERVICE_NAME=order-service \
ROVER_INSTANCE_ID=order-1 \
ROVER_SERVICE_HOST=127.0.0.1 \
ROVER_SERVICE_PORT=8080 \
php example.php
```

## 状态机语义

- 启动立即注册；失败后从请求完成时开始固定等待 5 秒再重试。
- 每个请求总超时默认 3 秒；同一 Registrar 始终只有一个请求在途。
- 注册成功后按本地配置固定每 5 秒心跳；不会根据响应动态改变调度间隔。
- 只有心跳返回 `404 / INSTANCE_NOT_FOUND` 时，才立即提交完整注册信息。
- 网络错误、408、429 和 5xx 固定间隔重试。
- 其他 4xx（包括鉴权失败和 `STALE_SESSION`）视为永久错误，进入 `FAILED`，避免无意义刷请求或旧进程抢回实例。
- 正常关闭最多尝试一次注销；失败不重试，最终由 Nameserver TTL 摘除。

在线实例只存在 Nameserver 内存中。Nameserver 重启后，仍存活的 Registrar 会在心跳收到 `INSTANCE_NOT_FOUND` 后重新注册，不依赖历史实例恢复。
