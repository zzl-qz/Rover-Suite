# Go HTTP Registrar 参考实现

这里的 `registrar.go` 是 Rover HTTP 注册协议的纯 Go 标准库参考代码，不是准备发布和长期兼容的完整 SDK。项目可以直接复制这一个文件到自己的 `internal/roverregistrar` 包，再按本身的配置系统进行裁剪。

它只负责服务提供方的生命周期：

- 应用端口就绪后立即发送完整注册信息；
- 请求结束后固定等待 5 秒，不做指数退避；
- 单次 HTTP 请求最多 3 秒，并且始终只有一个请求在途；
- 注册成功后每 5 秒发送心跳；
- 只有心跳收到 `404 INSTANCE_NOT_FOUND` 时才立即重新发送完整注册信息；
- 网络错误、`429` 和 `5xx` 固定等待 5 秒再尝试；
- `400/401/403/409/413` 会停止任务，并通过 `Errors()` 和 `Err()` 暴露原因；
- Client API 未启用时的 `404 NOT_FOUND`、`405` 和 `415` 也会停止，避免永久刷错路径或错误协议；
- `Close()` 先停止调度，再尽力注销一次。

## 最小接入

先确保业务端口已经开始监听，再启动 Registrar：

```go
r, err := registrar.New(registrar.Config{
    NameserverURL: "http://127.0.0.1:8889",
    Token:         "change-me",
    ServiceName:   "order-service",
    InstanceID:    os.Getenv("POD_UID"), // 也可以使用稳定的 host:port
    Host:          "10.0.1.20",
    Port:          8080,
    Weight:        100,
    Group:         "DEFAULT",
    Zone:          "shanghai-a",
    Metadata: map[string]string{
        "version": "1.2.0",
    },
})
if err != nil {
    return err
}
if err := r.Start(); err != nil {
    return err
}

signals := make(chan os.Signal, 1)
signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
select {
case <-signals:
case fatalErr, ok := <-r.Errors():
    if ok {
        log.Printf("Rover registration stopped: %v", fatalErr)
    }
}

// 注销失败不会恢复调度；实例最终仍会由 Nameserver TTL 摘除。
if err := r.Close(); err != nil {
    log.Printf("Rover best-effort unregister failed: %v", err)
}
```

`sessionId` 由参考实现用 `crypto/rand` 自动生成 UUID v4，并在完整注册、心跳和注销之间保持不变。Bearer token 只进入 `Authorization` 请求头，不写入 JSON。

## 验证

```bash
go test ./...
```

测试覆盖立即注册、固定延迟重试、网络/5xx 恢复、心跳 404 立即重新注册、终止类响应、单请求在途以及关闭注销。
