<?php

declare(strict_types=1);

require __DIR__ . '/RoverHttpRegistrar.php';

// 业务端口必须已经监听成功，再启动 Registrar。
$servicePort = (int)(getenv('ROVER_SERVICE_PORT') ?: '8080');
$instanceId = getenv('ROVER_INSTANCE_ID')
    ?: (gethostname() ?: 'localhost') . ':' . $servicePort;

$registrar = new RoverHttpRegistrar([
    'baseUrl' => getenv('ROVER_NAMESERVER_URL') ?: 'http://127.0.0.1:8889',
    'token' => getenv('ROVER_NAMESERVER_TOKEN') ?: '',
    'serviceName' => getenv('ROVER_SERVICE_NAME') ?: 'php-demo',
    'instanceId' => $instanceId,
    'host' => getenv('ROVER_SERVICE_HOST') ?: '127.0.0.1',
    'port' => $servicePort,
    'metadata' => ['language' => 'php'],
    'requestTimeoutMs' => 3_000,
    'retryIntervalMs' => 5_000,
    'heartbeatIntervalMs' => 5_000,
    'logger' => static function (string $message): void {
        fwrite(STDERR, '[rover] ' . $message . PHP_EOL);
    },
]);

$stop = false;
if (function_exists('pcntl_async_signals') && function_exists('pcntl_signal')) {
    pcntl_async_signals(true);
    pcntl_signal(SIGINT, static function () use (&$stop): void {
        $stop = true;
    });
    pcntl_signal(SIGTERM, static function () use (&$stop): void {
        $stop = true;
    });
} else {
    fwrite(STDERR, "pcntl 不可用：强制退出时由 Nameserver TTL 摘除实例。\n");
}

$registrar->run(static function () use (&$stop): bool {
    return $stop;
});

if ($registrar->lastError() !== null) {
    fwrite(STDERR, '[rover] lastError=' . $registrar->lastError() . PHP_EOL);
}
