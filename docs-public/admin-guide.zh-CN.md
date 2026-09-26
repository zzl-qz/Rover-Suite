# Rover-Admin 使用手册

[Admin API](./admin-api.zh-CN.md) · [文档索引](./README.md)

Rover-Admin 是可选的同源静态控制台。它不保存 Gateway 或 Nameserver 的业务配置，主要通过管理 API 读取快照，
并把路由和运行时配置更新转发给组件。

## 启动

```bash
mvn -pl rover-admin spring-boot:run
```

默认地址：`http://127.0.0.1:9090/`。生产环境请复制 `rover-admin/src/main/resources/application.yml` 到受保护
的外部配置，并设置 Gateway、Nameserver 管理地址和非空 `rover.admin.admin-token`。

## 页面

| 页面 | 用途 |
| --- | --- |
| 仪表盘 | QPS、延迟、状态码、在途请求、JVM 和 Nameserver 概览 |
| 请求追踪 | 查看 Gateway 已采样的请求时间线 |
| 路由管理 | 新增、修改和删除路由 |
| 实例管理 | 查看 Nameserver 注册实例及健康状态 |
| 最近事件 | 查看注册、注销、推送和剔除事件 |
| 配置管理 | 修改支持热更新的 Gateway/Nameserver 配置 |
| Agent 工作台 | 三栏工作台：左侧会话列表、中间多轮对话与调查进度、右侧当前事件上下文；只读采集路由、实例、指标和追踪证据，展示假设验证过程与结论，可选 AI 解读（实时流式显示） |

Agent 工作台主输入框只要求用自然语言描述问题（如「为什么 /api/demo/tt 调用失败？」），
不再强制先填路径；「高级上下文」折叠区可以手工指定 route / service / instance 与时间范围，全部可选。
同一个会话里的后续提问会作为追问落在当前事件上（「为什么没有实例？」「那昨天呢？」），
目标发生变化时才另开事件。会话、事件、任务与消息当前只存在 Admin 内存里，**重启即清空**，页面左侧有明确提示。

工作台在未配置模型时也能工作，直接给出规则诊断（含假设验证与证据）。如需 AI 解读，可设置以下环境变量：

| 变量 | 取值 |
| --- | --- |
| `ROVER_AGENT_MODEL_CHAT` | `openai` |
| `ROVER_AGENT_API_KEY` | 所选 OpenAI 兼容服务的密钥 |
| `ROVER_AGENT_BASE_URL` | 服务地址；该服务要求时需带 `/v1` |
| `ROVER_AGENT_MODEL` | 该服务支持且支持工具调用的模型名 |

这些环境变量现在只作为**首次启动播种**：仅在还没有模型配置文件时生效。在「模型配置」页保存过一次之后就以文件为准，
再改这些环境变量也不会覆盖文件。既有启动方式仍然可用，不算失效；长期使用建议改用「模型配置」页（见下文）。

密钥不要写入仓库。调查任务与工作台会话存于 Admin 内存，重启后消失。Agent 只读取管理快照，不会向业务路径发请求，也不会修改路由和配置。

配置模型后，工作台里调查卡片的「AI 解读」会边生成边显示（控制台用 SSE 订阅增量，卡片上显示「实时生成中...」；
连接断开时不影响调查，页面轮询会把最终全文补齐）。解读结束后以任务结果里的 `aiAnalysis` 为准；模型不可用或
未配置时该区块不出现，只展示规则诊断。调查卡片可展开「查看调查详情」查看每一步的执行情况、调查过程与结构化证据。

## 首次登录

配好口令后，控制台页面和全部 `/api/*` 都需要登录。启用登录的优先级如下：

- 配置 `password-hash`（`ROVER_ADMIN_PASSWORD_HASH`）最优先，填写 BCrypt 哈希。
- 明文 `password`（`ROVER_ADMIN_PASSWORD`）仅限本机使用，启动时会 WARN 建议改用 `password-hash`。
- 两项都留空时控制台**不启用鉴权**，仅限回环地址；启动会 WARN，页面顶栏会显示提示条。

生成 BCrypt 哈希（两种输出 `$2y$` / `$2b$` 都能被校验）：

```bash
htpasswd -bnBC 10 "" '你的口令' | tr -d ':\n'
python -c "import bcrypt;print(bcrypt.hashpw(b'你的口令',bcrypt.gensalt(10)).decode())"
```

第一条适用于 Linux/macOS/Git Bash/WSL，第二条需要 `pip install bcrypt`。

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| `ROVER_ADMIN_USERNAME` | `admin` | 控制台登录用户名 |
| `ROVER_ADMIN_PASSWORD_HASH` | 空 | 口令的 BCrypt 哈希，优先于明文 |
| `ROVER_ADMIN_PASSWORD` | 空 | 明文口令，启动期哈希进内存；启动会 WARN |
| `ROVER_ADMIN_MAX_LOGIN_FAILURES` | `5` | 窗口内同一来源允许的失败次数上限 |
| `ROVER_ADMIN_FAILURE_WINDOW_SECONDS` | `600` | 失败计数的窗口时长，单位秒 |

其他行为：

- 登录页：`/login.html`；会话超时 30 分钟。
- 登出只认 `POST /api/logout`（GET 登出无法被 CSRF 保护，故不支持）。
- 同一来源连续失败达到上限后，`POST /login` 直接回 `429`。

Windows 上没有 POSIX 文件权限，可用下面命令去掉继承权限并只授予当前用户，加固模型配置文件：

```
icacls "<file>" /inheritance:r /grant:r "%USERNAME%:F"
```

## 模型配置

入口是控制台左侧导航「模型配置」。表单是"预设下拉 + 自定义输入"：选择预设后仍可手动修改服务地址和模型名。

四个按钮：

- **保存并生效**：保存配置并立即替换客户端，**无需重启 Admin**。
- **测试连接（不保存）**：用表单当前候选值探活，不落盘、不影响已生效配置，固定 8 秒超时。
- **验证已生效配置**：对当前已生效的配置再跑一次连接测试。
- **清除已存密钥**：清除已保存的 API Key。

密钥处理：API Key 留空 = 不改动已存密钥；只有点「清除已存密钥」或在保存时传 `clearApiKey:true` 才会清除。

**如何证明不重启即生效**：保存后「生效状态」卡的"生效版本 #N"与"生效时间"会变化；也可以调用 `POST /api/model/verify`
拿到 `buildId`，确认生效的正是刚保存的配置。构建失败时会保留上一个可用客户端，并在 `lastError` 里给出原因。

存储与备份：

- 模型配置文件（`ROVER_ADMIN_MODEL_CONFIG_FILE`，默认工作目录下的 `config/admin-model.properties`）保存非密钥字段，
  其中 `api-key-enc` 是 AES-256-GCM 密文。它和 Gateway / Nameserver 的本地运行时配置放在同一个 `config/` 目录里，
  随项目一起维护与备份；整个 `config/` 已在 `.gitignore` 中，文件缺失时启动会自动创建一个空配置。
- 主密钥文件（`ROVER_ADMIN_MASTER_KEY_FILE`，默认与模型配置文件同级的 `master.key`）在首次加密时自动生成。
- 密钥与主密钥文件必须**分开备份**：放在一起等于没加密。
- Windows 上没有 POSIX 文件权限，机密性来自 AES-GCM 加密而不是文件模式；建议用
  `icacls "<file>" /inheritance:r /grant:r "%USERNAME%:F"` 加固（启动日志会打印两条加固命令）。

| 变量 | 默认值 | 含义 |
| --- | --- | --- |
| `ROVER_ADMIN_MODEL_CONFIG_FILE` | 工作目录下 `config/admin-model.properties` | 模型配置文件路径 |
| `ROVER_ADMIN_MASTER_KEY` | 空 | base64 解出 32 字节则直接用作 AES 密钥，否则按口令用 PBKDF2-HMAC-SHA256（65536 轮）派生 |
| `ROVER_ADMIN_MASTER_KEY_FILE` | 与配置文件同级的 `master.key` | 主密钥文件；首次加密时自动生成 32 字节随机主密钥并落盘 |

内置预设（选预设后仍可手改）：

| 预设 | 服务地址 | 模型名 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| 阿里云百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-air` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5:7b` |
| 本地 vLLM | `http://127.0.0.1:8000/v1` | `Qwen2.5-7B-Instruct` |

页面还包含三张状态卡：生效状态、连接测试、效果验证。

## 页面速览

下面的截图来自本地演示环境，地址、服务名、实例和请求数据均为演示数据。它们用于说明页面职责和常用操作，
不代表固定的默认数据量。

### 1. 仪表盘：先看流量，再看资源

![Admin 仪表盘概览](images/admin/01-dashboard-overview.png)

仪表盘把数据分成三层：

- **瞬时**：上一整秒完成的请求、在途请求和当前连接数；空闲时显示 0 是正常的。
- **近窗**：最近 1 分钟或 5 分钟的 QPS、延迟、状态码、路由 Top 和错误。
- **累计/进程**：JVM 堆、CPU、线程、GC、运行时长和进程号。

QPS 曲线的纵轴会按实际峰值自动调整，它不是 Gateway 的容量上限。状态码图用于区分正常响应、客户端问题和 Gateway / 上游问题。

![Admin 仪表盘进程与环境](images/admin/02-dashboard-process.png)

进程区域适合排查“请求变慢但错误率不高”的情况：观察堆使用、老年代、CPU、线程和 GC 是否持续上升；环境区域则确认
Gateway 端口、发现模式、负载均衡策略、Nameserver 端口和心跳超时。

### 2. 请求追踪：定位慢在哪里

![请求追踪列表](images/admin/03-traces-list.png)

请求追踪只显示 Gateway 已采样的请求。可以按 `traceId`、路径或“只看慢请求”过滤。采样率越高，观测越完整，但对
Gateway 的记录和内存开销也越大。

![请求追踪阶段详情](images/admin/04-traces-detail.png)

点击一行会展开阶段耗时：接收/解码、Filter 链、路由匹配、服务发现、负载均衡、上游处理和响应写回。通常应先看占比最高
的阶段；上游处理占比高，优先检查后端服务，而不是先调 Gateway。

### 3. 路由管理：修改请求如何到达服务

![路由列表](images/admin/05-routes-list.png)

路由列表展示 `businessPrefix`、服务名、静态目标和 `stripPrefix`。保存后会请求 Gateway 热更新并落盘；修改前应确认前缀
不会与其他路由重叠。

![路由编辑抽屉](images/admin/06-route-editor.png)

编辑时最重要的是：

- 动态发现时先选「注册中心」或「静态地址」。选注册中心填 `serviceName`；选静态地址填 `targetUrl` / `targetUrls`。
- 整机是 `static` 时只填静态地址。
- 同一条路由不要两套字段都留着。
- `stripPrefix` 决定转发给上游时是否移除匹配前缀。建议每条自己写，不要依赖全局 `rewrite.stripPrefix`。

### 4. 实例管理：确认 Nameserver 是否有可用后端

![实例管理](images/admin/07-instances.png)

实例页展示服务名、实例 ID、地址、分组、健康状态、临时实例、权重、最近心跳和空闲时间。Gateway 找不到服务时，先在这里
确认实例是否存在且健康，再检查路由和服务名是否一致。

### 5. 最近事件：看 Nameserver 发生过什么

![最近事件](images/admin/08-events.png)

事件列表是固定容量的环形缓冲，最多保留最近 200 条。筛选器固定包含注册、注销、心跳剔除、标记不健康和变更推送五类；没有
发生过的类型筛选后会显示 0 条。心跳和查询等高频操作只进入指标计数，不逐条写入事件列表，以免淹没生命周期事件。

### 6. 配置管理：只改明确支持热更新的项

![配置管理概览](images/admin/09-configs-overview.png)

配置页按 Gateway 和 Nameserver 展示。绿色“可热更新”表示保存后可以立即应用；修改后才会出现保存按钮，撤销可以恢复本次读取
到的值。Gateway 区域还包含默认关闭的本地限流：可选择令牌桶或滑动窗口、配额维度和阈值；它按单个 Gateway 实例计数，不能替代
需要跨实例一致性的业务限流。同一页还有默认关闭的进程内熔断：连续失败阈值、休息时间和 `recovery`（到期全开 / 只发一个探测）。
熔断按上游 `host:port` 计数，不跨 Gateway 共享。`gateway.retry.enabled` 打开后，连不上且请求还没发出去时换下一台，最多 1 次。

主动实现 `ConfigurablePlugin` 的插件会显示在 Gateway 配置中，和内置配置一样支持保存、撤销、校验与热更新；
只有当前已装配且主动声明配置项的插件会出现。没有实现该可选接口时，不出现插件配置项是正常状态，不代表插件未加载。

![配置管理详情](images/admin/10-configs-detail.png)

几个容易混淆的配置：

- `gateway.loadbalance.strategy` 属于启动/插件装配配置，不在 Admin 配置管理中修改；请在 `rover-gateway.yml` 中配置内置策略、SPI `name()` 或实现类全名，然后重启 Gateway。
- `gateway.trace.sampleRate` 才接受 `0~1` 的小数，`0` 表示只记录慢请求，`1` 表示全量记录。
- 指标窗口单位是秒，超时和健康检查配置通常是毫秒。

保存失败时避免连续重复提交；先看字段类型、取值范围和组件日志。Admin 只是管理面，最终校验和生效仍由 Gateway 或
Nameserver 完成。

## 轻量使用原则

- 仪表盘可见时才请求实时数据；离开页面或切到后台标签会停止实时轮询。
- 非仪表盘页面只做低频探活和按页面读取，避免对 Gateway/Nameserver 产生观察流量。
- Admin 不直连业务服务，只调用组件管理口。
- 生产环境应限制 `9090` 的访问来源，避免把 Admin 暴露到公网。

## 常见操作

负载均衡策略请在 Gateway 配置文件里选择 `round_robin`、`random`、`weighted_round_robin`、`ip_hash`、`least_connections`，或填写插件 SPI `name()` / 实现类全名。
`gateway.trace.sampleRate` 才是可在 Admin 中热更新的 0~1 小数采样率。

保存失败时先查看具体字段错误，避免重复提交同一个无效值；失败不会代表组件已经应用了新配置。
