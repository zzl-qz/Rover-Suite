<?php

declare(strict_types=1);

/**
 * Rover HTTP Registration API 的可复制参考实现。
 *
 * 只适用于 CLI、Swoole、RoadRunner、Octane 等长驻进程。调用方必须周期调用 tick()，
 * 或在一个唯一的长驻协程/任务中调用 run()。普通 PHP-FPM 请求生命周期不受支持。
 */
final class RoverHttpRegistrar
{
    public const STATE_NEW = 'NEW';
    public const STATE_REGISTERING = 'REGISTERING';
    public const STATE_REGISTERED = 'REGISTERED';
    public const STATE_FAILED = 'FAILED';
    public const STATE_STOPPING = 'STOPPING';
    public const STATE_CLOSED = 'CLOSED';

    private const REGISTER_PATH = '/v1/client/instances/register';
    private const HEARTBEAT_PATH = '/v1/client/instances/heartbeat';
    private const UNREGISTER_PATH = '/v1/client/instances/unregister';
    private const MIN_INTERVAL_MS = 1_000;
    private const MAX_INTERVAL_MS = 8_000;
    private const MAX_RESPONSE_BYTES = 64 * 1024;

    /** @var array<string, mixed> */
    private array $config;
    private string $sessionId;
    private string $state = self::STATE_NEW;
    private ?string $lastError = null;
    private bool $started = false;
    private bool $inFlight = false;
    private bool $closeRequested = false;
    private bool $closed = false;
    private int $nextAttemptAtMs = 0;
    private int $heartbeatIntervalMs;

    /** @var resource|object cURL handle；不写死 CurlHandle 类型以兼容 PHP 7.4/8.x */
    private $curl;
    /** @var callable(string):void|null */
    private $logger;

    /**
     * @param array<string, mixed> $config
     *
     * 必填：baseUrl、serviceName、instanceId、host、port。
     * 可选：token、weight、group、zone、metadata、requestTimeoutMs、
     * retryIntervalMs、heartbeatIntervalMs、logger。
     */
    public function __construct(array $config)
    {
        if (!extension_loaded('curl')) {
            throw new RuntimeException('RoverHttpRegistrar 需要 PHP cURL 扩展');
        }

        $baseUrl = rtrim($this->requiredString($config, 'baseUrl'), '/');
        if (!preg_match('#^https?://#i', $baseUrl)) {
            throw new InvalidArgumentException('baseUrl 必须以 http:// 或 https:// 开头');
        }
        $parsedBaseUrl = parse_url($baseUrl);
        if (!is_array($parsedBaseUrl) || empty($parsedBaseUrl['host'])) {
            throw new InvalidArgumentException('baseUrl 必须包含有效 host');
        }
        $port = (int)($config['port'] ?? 0);
        if ($port < 1 || $port > 65535) {
            throw new InvalidArgumentException('port 必须在 1-65535 之间');
        }

        $retryIntervalMs = (int)($config['retryIntervalMs'] ?? 5_000);
        $heartbeatIntervalMs = (int)($config['heartbeatIntervalMs'] ?? 5_000);
        $this->requireInterval($retryIntervalMs, 'retryIntervalMs');
        $this->requireInterval($heartbeatIntervalMs, 'heartbeatIntervalMs');
        $requestTimeoutMs = (int)($config['requestTimeoutMs'] ?? 3_000);
        if ($requestTimeoutMs < 1) {
            throw new InvalidArgumentException('requestTimeoutMs 必须大于 0');
        }

        $metadata = $config['metadata'] ?? [];
        if (!is_array($metadata)) {
            throw new InvalidArgumentException('metadata 必须是字符串键值对象');
        }
        foreach ($metadata as $key => $value) {
            if (!is_string($key) || $key === '' || !is_string($value)) {
                throw new InvalidArgumentException('metadata 的 key/value 必须是非空字符串 key 和字符串 value');
            }
        }

        $this->config = [
            'baseUrl' => $baseUrl,
            'token' => (string)($config['token'] ?? ''),
            'serviceName' => $this->requiredString($config, 'serviceName'),
            'instanceId' => $this->requiredString($config, 'instanceId'),
            'host' => $this->requiredString($config, 'host'),
            'port' => $port,
            'weight' => (int)($config['weight'] ?? 100),
            'group' => $this->optionalString($config['group'] ?? null),
            'zone' => $this->optionalString($config['zone'] ?? null),
            'metadata' => $metadata,
            'requestTimeoutMs' => $requestTimeoutMs,
            'retryIntervalMs' => $retryIntervalMs,
        ];
        $this->heartbeatIntervalMs = $heartbeatIntervalMs;
        $this->sessionId = self::uuidV4();
        $this->logger = isset($config['logger']) && is_callable($config['logger'])
            ? $config['logger']
            : null;
        $this->curl = curl_init();
        if ($this->curl === false) {
            throw new RuntimeException('初始化 cURL 失败');
        }
    }

    /** 启动后立即执行第一次注册；重复调用不会创建第二个调度器。 */
    public function start(): void
    {
        if ($this->closed) {
            throw new LogicException('Registrar 已关闭，不能重新启动');
        }
        if ($this->state !== self::STATE_NEW) {
            return;
        }
        $this->started = true;
        $this->state = self::STATE_REGISTERING;
        $this->nextAttemptAtMs = self::monotonicMillis();
        $this->tick();
    }

    /**
     * 阻塞运行状态机，适合独立 CLI 进程，或放入框架提供的唯一长驻协程/任务。
     * $shouldStop 返回 true 时退出并尽力注销。
     *
     * @param callable():bool $shouldStop
     */
    public function run(callable $shouldStop): void
    {
        $this->start();
        try {
            while (!$shouldStop() && !$this->isTerminal()) {
                $this->tick();
                $sleepMs = max(10, min(100, $this->millisecondsUntilNextAttempt()));
                usleep($sleepMs * 1_000);
            }
        } finally {
            $this->close();
        }
    }

    /**
     * 非阻塞调度入口。网络请求本身是同步的，最长受 requestTimeoutMs 限制；
     * 调用方可每 50-100ms 从自己的事件循环/定时器调用一次。
     */
    public function tick(): void
    {
        if ($this->closed || $this->closeRequested || $this->inFlight || $this->isTerminal()) {
            return;
        }
        if ($this->state === self::STATE_NEW) {
            $this->start();
            return;
        }
        if (self::monotonicMillis() < $this->nextAttemptAtMs) {
            return;
        }

        if ($this->state === self::STATE_REGISTERING) {
            $this->attemptRegister();
        } elseif ($this->state === self::STATE_REGISTERED) {
            $this->attemptHeartbeat();
        }

        // Swoole 等协程环境可能在 cURL 让出执行权时从另一个协程调用 close()。
        if ($this->closeRequested && !$this->inFlight && !$this->closed) {
            $this->finishClose();
        }
    }

    /** 停止调度，并在当前 session 仍有效时最多尝试一次注销；注销失败不会重试。 */
    public function close(): void
    {
        if ($this->closed) {
            return;
        }
        $this->closeRequested = true;
        $this->state = self::STATE_STOPPING;
        if ($this->inFlight) {
            return;
        }
        $this->finishClose();
    }

    public function state(): string
    {
        return $this->state;
    }

    public function sessionId(): string
    {
        return $this->sessionId;
    }

    public function lastError(): ?string
    {
        return $this->lastError;
    }

    public function isTerminal(): bool
    {
        return $this->state === self::STATE_FAILED || $this->state === self::STATE_CLOSED;
    }

    public function millisecondsUntilNextAttempt(): int
    {
        return max(0, $this->nextAttemptAtMs - self::monotonicMillis());
    }

    public function __destruct()
    {
        try {
            $this->close();
        } catch (Throwable $ignored) {
            // 析构阶段不能把异常传播到业务退出链路。
        }
    }

    private function attemptRegister(): void
    {
        $result = $this->request(self::REGISTER_PATH, $this->registrationPayload());
        if ($this->closeRequested) {
            return;
        }
        if ($this->isSuccess($result)) {
            if (!$this->hasOkCode($result)) {
                $this->scheduleRegisterRetry('注册响应不是有效的 Rover JSON');
                return;
            }
            $this->lastError = null;
            $this->state = self::STATE_REGISTERED;
            $this->nextAttemptAtMs = self::monotonicMillis() + $this->heartbeatIntervalMs;
            $this->log('注册成功，进入 REGISTERED');
            return;
        }
        if ($this->isTransient($result)) {
            $this->scheduleRegisterRetry($this->failureDescription('注册', $result));
            return;
        }
        $this->fail($this->failureDescription('注册', $result));
    }

    private function attemptHeartbeat(): void
    {
        $result = $this->request(self::HEARTBEAT_PATH, $this->sessionPayload());
        if ($this->closeRequested) {
            return;
        }
        if ($this->isSuccess($result)) {
            if (!$this->hasOkCode($result)) {
                $this->scheduleHeartbeatRetry('心跳响应不是有效的 Rover JSON');
                return;
            }
            $this->lastError = null;
            $this->nextAttemptAtMs = self::monotonicMillis() + $this->heartbeatIntervalMs;
            return;
        }

        $code = is_array($result['json']) ? (string)($result['json']['code'] ?? '') : '';
        if ($result['status'] === 404 && $code === 'INSTANCE_NOT_FOUND') {
            $this->state = self::STATE_REGISTERING;
            $this->log('心跳发现实例不存在，立即提交完整注册信息');
            $this->attemptRegister();
            return;
        }
        if ($this->isTransient($result)) {
            $this->scheduleHeartbeatRetry($this->failureDescription('心跳', $result));
            return;
        }
        $this->fail($this->failureDescription('心跳', $result));
    }

    private function finishClose(): void
    {
        if ($this->closed) {
            return;
        }
        $this->state = self::STATE_STOPPING;
        if ($this->started && !$this->inFlight) {
            // 注册响应可能丢失，因此即使本地还未确认 registered，也用同一 session
            // best-effort 注销一次；请求幂等且 404 可以直接忽略。
            $this->request(self::UNREGISTER_PATH, $this->sessionPayload());
        }
        $this->closed = true;
        $this->state = self::STATE_CLOSED;
        if ($this->curl !== null) {
            curl_close($this->curl);
            $this->curl = null;
        }
    }

    /**
     * @param array<string, mixed> $payload
     * @return array{status:int, body:string, json:?array, networkError:?string}
     */
    private function request(string $path, array $payload): array
    {
        if ($this->inFlight) {
            throw new LogicException('同一 Registrar 只允许一个在途请求');
        }
        $this->inFlight = true;
        try {
            $body = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
            $headers = ['Content-Type: application/json', 'Accept: application/json'];
            if ($this->config['token'] !== '') {
                $headers[] = 'Authorization: Bearer ' . $this->config['token'];
            }

            $responseBody = '';
            $responseTooLarge = false;
            curl_reset($this->curl);
            curl_setopt_array($this->curl, [
                CURLOPT_URL => $this->config['baseUrl'] . $path,
                CURLOPT_POST => true,
                CURLOPT_POSTFIELDS => $body,
                CURLOPT_HTTPHEADER => $headers,
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_CONNECTTIMEOUT_MS => $this->config['requestTimeoutMs'],
                CURLOPT_TIMEOUT_MS => $this->config['requestTimeoutMs'],
                CURLOPT_NOSIGNAL => true,
                CURLOPT_WRITEFUNCTION => static function ($handle, string $chunk) use (
                    &$responseBody,
                    &$responseTooLarge
                ): int {
                    $chunkBytes = strlen($chunk);
                    if ($chunkBytes > self::MAX_RESPONSE_BYTES - strlen($responseBody)) {
                        $responseTooLarge = true;
                        return 0;
                    }
                    $responseBody .= $chunk;
                    return $chunkBytes;
                },
            ]);
            $executed = curl_exec($this->curl);
            if ($executed === false) {
                return [
                    'status' => 0,
                    'body' => '',
                    'json' => null,
                    'networkError' => $responseTooLarge
                        ? '响应体超过 ' . self::MAX_RESPONSE_BYTES . ' 字节'
                        : curl_error($this->curl),
                ];
            }
            $status = (int)curl_getinfo($this->curl, CURLINFO_RESPONSE_CODE);
            $decoded = null;
            try {
                $candidate = json_decode($responseBody, true, 512, JSON_THROW_ON_ERROR);
                $decoded = is_array($candidate) ? $candidate : null;
            } catch (JsonException $ignored) {
                // 由状态机按无效响应处理；错误页可能本来就不是 JSON。
            }
            return [
                'status' => $status,
                'body' => $responseBody,
                'json' => $decoded,
                'networkError' => null,
            ];
        } catch (JsonException $exception) {
            return [
                'status' => 0,
                'body' => '',
                'json' => null,
                'networkError' => 'JSON 编码失败: ' . $exception->getMessage(),
            ];
        } finally {
            $this->inFlight = false;
        }
    }

    /** @return array<string, mixed> */
    private function registrationPayload(): array
    {
        $payload = [
            'serviceName' => $this->config['serviceName'],
            'instanceId' => $this->config['instanceId'],
            'sessionId' => $this->sessionId,
            'host' => $this->config['host'],
            'port' => $this->config['port'],
            'weight' => $this->config['weight'],
            'metadata' => $this->config['metadata'],
        ];
        if ($this->config['group'] !== null) {
            $payload['group'] = $this->config['group'];
        }
        if ($this->config['zone'] !== null) {
            $payload['zone'] = $this->config['zone'];
        }
        return $payload;
    }

    /** @return array<string, string> */
    private function sessionPayload(): array
    {
        return [
            'serviceName' => $this->config['serviceName'],
            'instanceId' => $this->config['instanceId'],
            'sessionId' => $this->sessionId,
        ];
    }

    /**
     * @param array{status:int, body:string, json:?array, networkError:?string} $result
     */
    private function isSuccess(array $result): bool
    {
        return $result['networkError'] === null && $result['status'] >= 200 && $result['status'] < 300;
    }

    /**
     * @param array{status:int, body:string, json:?array, networkError:?string} $result
     */
    private function hasOkCode(array $result): bool
    {
        $json = $result['json'];
        return is_array($json) && ($json['code'] ?? null) === 'OK';
    }

    /**
     * 网络错误、408、429 和 5xx 固定间隔重试；其余 4xx 属于永久配置/会话错误。
     *
     * @param array{status:int, body:string, json:?array, networkError:?string} $result
     */
    private function isTransient(array $result): bool
    {
        if ($result['networkError'] !== null || $result['status'] === 0) {
            return true;
        }
        return $result['status'] === 408
            || $result['status'] === 429
            || ($result['status'] >= 500 && $result['status'] <= 599);
    }

    /**
     * @param array{status:int, body:string, json:?array, networkError:?string} $result
     */
    private function failureDescription(string $operation, array $result): string
    {
        if ($result['networkError'] !== null) {
            return $operation . '网络错误: ' . $result['networkError'];
        }
        $code = is_array($result['json']) ? (string)($result['json']['code'] ?? '') : '';
        $message = is_array($result['json']) ? (string)($result['json']['message'] ?? '') : '';
        return sprintf('%s失败: HTTP %d%s%s',
            $operation,
            $result['status'],
            $code === '' ? '' : ', code=' . $code,
            $message === '' ? '' : ', message=' . $message);
    }

    private function scheduleRegisterRetry(string $message): void
    {
        $this->lastError = $message;
        $this->state = self::STATE_REGISTERING;
        $this->nextAttemptAtMs = self::monotonicMillis() + $this->config['retryIntervalMs'];
        $this->log($message . '；固定 ' . $this->config['retryIntervalMs'] . 'ms 后重试注册');
    }

    private function scheduleHeartbeatRetry(string $message): void
    {
        $this->lastError = $message;
        $this->state = self::STATE_REGISTERED;
        $this->nextAttemptAtMs = self::monotonicMillis() + $this->config['retryIntervalMs'];
        $this->log($message . '；固定 ' . $this->config['retryIntervalMs'] . 'ms 后重试心跳');
    }

    private function fail(string $message): void
    {
        $this->lastError = $message;
        $this->state = self::STATE_FAILED;
        $this->log($message . '；属于永久错误，Registrar 已停止');
    }

    private function requiredString(array $values, string $key): string
    {
        $value = $values[$key] ?? null;
        if (!is_string($value) || trim($value) === '') {
            throw new InvalidArgumentException($key . ' 不能为空');
        }
        return trim($value);
    }

    private function optionalString($value): ?string
    {
        if ($value === null || $value === '') {
            return null;
        }
        if (!is_string($value)) {
            throw new InvalidArgumentException('group/zone 必须是字符串');
        }
        return trim($value) === '' ? null : trim($value);
    }

    private function requireInterval(int $value, string $name): void
    {
        if ($value < self::MIN_INTERVAL_MS || $value > self::MAX_INTERVAL_MS) {
            throw new InvalidArgumentException(
                $name . ' 必须在 ' . self::MIN_INTERVAL_MS . '-' . self::MAX_INTERVAL_MS . 'ms 之间');
        }
    }

    private function log(string $message): void
    {
        if ($this->logger !== null) {
            ($this->logger)($message);
        }
    }

    private static function monotonicMillis(): int
    {
        return (int)(hrtime(true) / 1_000_000);
    }

    private static function uuidV4(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        $hex = bin2hex($bytes);
        return sprintf('%s-%s-%s-%s-%s',
            substr($hex, 0, 8),
            substr($hex, 8, 4),
            substr($hex, 12, 4),
            substr($hex, 16, 4),
            substr($hex, 20, 12));
    }
}
