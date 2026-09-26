# Rover-Admin User Guide

[Admin API](./admin-api.md) · [Documentation index](./README.md)

Rover-Admin is an optional same-origin static console. It does not store
Gateway or Nameserver business configuration. It reads management snapshots
and forwards route/runtime configuration changes to the components.

## Start

```bash
mvn -pl rover-admin spring-boot:run
```

Default URL: `http://127.0.0.1:9090/`. In production, copy
`rover-admin/src/main/resources/application.yml` to a protected external
configuration and set the Gateway/Nameserver management URLs and a non-empty
`rover.admin.admin-token`.

The Agent Workbench is a three-column layout: the session list on the left, the multi-turn conversation and
investigation progress in the middle, and the active incident context on the right. The main input is a single natural
language box (e.g. "why does /api/demo/tt fail?"); the collapsible "advanced context" lets you pin route / service /
instance and a time range, all optional. Later questions in the same session are treated as follow-ups on the active
incident ("why are there no instances?", "what about yesterday?"), and a new incident opens only when the target
changes. Sessions, incidents, tasks, and messages currently live in Admin memory only — **everything is lost on
restart**, which the left column states explicitly.

The workbench works with route and instance snapshots even when no model is configured. To enable AI explanations,
set these environment variables:

| Variable | Value |
| --- | --- |
| `ROVER_AGENT_MODEL_CHAT` | `openai` |
| `ROVER_AGENT_API_KEY` | API key for the selected OpenAI-compatible service |
| `ROVER_AGENT_BASE_URL` | Service base URL; include `/v1` when that service requires it |
| `ROVER_AGENT_MODEL` | Model name supported by that service and tool calling |

These environment variables now act only as **first-boot seeding**: they apply
only while no model configuration file exists. Once you have saved from the
Model configuration page, the file wins and later changes to these variables do
not overwrite it. The existing startup method still works and is not deprecated,
but the Model configuration page (below) is the recommended long-term path.

Keep the key outside the repository. Investigations and workbench sessions are
held in Admin memory and disappear after restart. The Agent only reads management
snapshots; it does not send requests to business paths or change routes and
configuration.

Once a model is configured, the "AI interpretation" section of an investigation
card in the workbench fills in as the text is generated (the console subscribes
to deltas over SSE and shows "generating live..."; a dropped connection does not
affect the investigation, and polling fills in the final text). When generation
finishes, `aiAnalysis` in the task result is authoritative. If the model is
unavailable or not configured the section does not appear and only the rule-based
diagnosis is shown. Expanding "view investigation details" on the card shows each
step, the investigation trail, and the structured evidence.

## First login

Once a credential is configured, both the console pages and all `/api/*`
endpoints require login. Enable login in this priority order:

- Set `password-hash` (`ROVER_ADMIN_PASSWORD_HASH`) to a BCrypt hash. This has
  the highest priority.
- Set the plaintext `password` (`ROVER_ADMIN_PASSWORD`) only for local use;
  startup logs a WARN recommending `password-hash`.
- If both are empty, the console does **not** require authentication, which is
  only acceptable on loopback: startup logs a WARN and the page header shows a
  banner.

Generate a BCrypt hash (both `$2y$` and `$2b$` output verify):

```bash
htpasswd -bnBC 10 "" 'your-password' | tr -d ':\n'
python -c "import bcrypt;print(bcrypt.hashpw(b'your-password',bcrypt.gensalt(10)).decode())"
```

The first command works on Linux/macOS/Git Bash/WSL; the second needs
`pip install bcrypt`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ROVER_ADMIN_USERNAME` | `admin` | Console login user name |
| `ROVER_ADMIN_PASSWORD_HASH` | empty | BCrypt hash of the password, takes priority over plaintext |
| `ROVER_ADMIN_PASSWORD` | empty | Plaintext password, hashed into memory at startup; startup logs a WARN |
| `ROVER_ADMIN_MAX_LOGIN_FAILURES` | `5` | Failed logins allowed per source within the window |
| `ROVER_ADMIN_FAILURE_WINDOW_SECONDS` | `600` | Failure-counting window, in seconds |

Other behavior:

- Login page: `/login.html`; session timeout is 30 minutes.
- Logout is `POST /api/logout` only (a GET logout cannot be CSRF-protected, so
  it is not supported).
- After the failure limit is reached from one source, `POST /login` returns `429`.

Windows has no POSIX file permissions, so you can harden the model
configuration file by removing inherited permissions and granting only the
current user:

```
icacls "<file>" /inheritance:r /grant:r "%USERNAME%:F"
```

## Model configuration

The entry point is the "Model configuration" item in the console's left
navigation. The form combines a preset dropdown with free-text fields: after
choosing a preset you can still edit the base URL and model name.

The four buttons:

- **Save and apply**: saves the configuration and swaps in the new client
  immediately; **no Admin restart is needed**.
- **Test connection (no save)**: probes connectivity with the current form
  values; nothing is written and the active configuration is untouched, with a
  fixed 8-second timeout.
- **Verify active configuration**: runs the connectivity test again against the
  configuration currently in effect.
- **Clear stored key**: removes the stored API key.

Key handling: leaving the API Key field empty keeps the stored key; it is
cleared only by **Clear stored key** or by sending `clearApiKey:true` when
saving.

**Proving it took effect without a restart**: after saving, the "effective
version #N" and "effective time" on the **Effective status** card change; you
can also call `POST /api/model/verify` and read `buildId` to confirm that the
configuration in effect is the one you just saved. If building the client
fails, the previous working client is kept and the reason is reported in
`lastError`.

Storage and backup:

- The model configuration file (`ROVER_ADMIN_MODEL_CONFIG_FILE`, default
  `config/admin-model.properties` under the working directory) holds the non-secret fields;
  its `api-key-enc` field is AES-256-GCM ciphertext. It sits in the same `config/` directory as the
  Gateway / Nameserver local runtime configs, so it is maintained and backed up with the project;
  the whole `config/` directory is gitignored and an empty config is created on startup when missing.
- The master key file (`ROVER_ADMIN_MASTER_KEY_FILE`, default `master.key` next
  to the configuration file) is generated automatically on first encryption.
- Back up the key and the master key file **separately**: keeping them together
  is the same as not encrypting.
- Windows has no POSIX file permissions, so confidentiality comes from AES-GCM
  encryption rather than file mode. Hardening with
  `icacls "<file>" /inheritance:r /grant:r "%USERNAME%:F"` is recommended
  (startup logs print two hardening commands).

| Variable | Default | Meaning |
| --- | --- | --- |
| `ROVER_ADMIN_MODEL_CONFIG_FILE` | `config/admin-model.properties` under the working directory | Model configuration file path |
| `ROVER_ADMIN_MASTER_KEY` | empty | A base64 value that decodes to 32 bytes is used directly as the AES key; otherwise it is treated as a passphrase and derived with PBKDF2-HMAC-SHA256 (65536 rounds) |
| `ROVER_ADMIN_MASTER_KEY_FILE` | `master.key` next to the configuration file | Master key file; a 32-byte random key is written on first encryption |

Built-in presets (still editable after selection):

| Preset | Base URL | Model |
| --- | --- | --- |
| OpenAI | `https://api.openai.com` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |
| Alibaba Cloud Bailian | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-air` |
| Local Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5:7b` |
| Local vLLM | `http://127.0.0.1:8000/v1` | `Qwen2.5-7B-Instruct` |

The page also shows three status cards: effective status, connection test and
effect verification.

## Pages

| Page | Purpose |
| --- | --- |
| Dashboard | QPS, latency, status codes, in-flight requests, JVM and registry overview |
| Request tracing | Sampled Gateway request timelines |
| Routes | Create, update and delete routes |
| Instances | Registered Nameserver instances and health |
| Recent events | Registration, removal, health and push events |
| Configuration | Runtime Gateway/Nameserver settings |
| Diagnosis | Hypothesis-based read-only investigation over route, instance, metric, trace, configuration, and registry-event evidence, with an optional streamed-live AI explanation |

## Screenshots and quick orientation

The following screenshots come from a local demo environment. Addresses,
service names, timestamps and traffic are demonstration data.

### Dashboard

![Admin dashboard overview](images/admin/01-dashboard-overview.png)

The dashboard separates **instant** values (the previous full second), **near
window** values (the selected 1m/5m window), and **cumulative/process** values
(JVM, CPU, threads, GC and uptime). The QPS axis follows the observed peak; it
is not a Gateway capacity limit.

![Admin process and environment](images/admin/02-dashboard-process.png)

Use the process section when latency rises without an obvious error-rate
increase. Check heap, old generation, CPU, threads and GC, then confirm the
Gateway port, discovery mode, load-balancer strategy and Nameserver settings.

### Request tracing

![Request tracing list](images/admin/03-traces-list.png)

Tracing contains only sampled requests. Filter by `traceId`, path or slow
requests. Higher sampling improves visibility but increases Gateway recording
and memory overhead.

![Request tracing phases](images/admin/04-traces-detail.png)

Expand a row to inspect decode, filters, route matching, discovery,
load-balancing, upstream processing and response writing. Start with the phase
that owns the largest share of total time.

### Routes

![Route list](images/admin/05-routes-list.png)

The route list shows `businessPrefix`, service name, static targets and
`stripPrefix`. Saving applies the update to Gateway and persists it. Check for
overlapping prefixes before changing a route.

![Route editor](images/admin/06-route-editor.png)

In dynamic discovery, pick registry or static address first. Registry routes
use `serviceName`; static routes use `targetUrl` or `targetUrls`. Do not keep
both on one route. `stripPrefix` is removed before the request reaches the
upstream; set it per route instead of relying on global `rewrite.stripPrefix`.

### Instances

![Instances](images/admin/07-instances.png)

Check service name, instance address, group, health, ephemeral status, weight,
last heartbeat and idle time. When Gateway cannot find a service, verify this
page before debugging the route.

### Recent events

![Recent events](images/admin/08-events.png)

The ring buffer retains up to 200 recent events. The filter always contains
registration, unregistration, expiration eviction, unhealthy marking and
change push. High-frequency heartbeats and queries remain counters rather than
individual events so they do not hide lifecycle changes.

### Configuration

![Configuration overview](images/admin/09-configs-overview.png)

Green “hot reload” badges identify settings that can be applied immediately.
Save controls appear only after a value changes. Gateway also exposes the
default-off local rate limiter: choose token bucket or sliding window, a quota
key, and thresholds. It counts per Gateway instance and is not a replacement
for a business limit shared across instances. The same page also has a
default-off process-local circuit breaker: failure threshold, rest window, and
`recovery` (`all` after the rest, or `half` for one probe). It counts per
upstream `host:port` and is not shared across Gateway replicas.
`gateway.retry.enabled` retries one other instance when connect failed and the
request was never sent.

Plugins that explicitly implement `ConfigurablePlugin` are shown with the
Gateway settings. Their fields use the same save and rollback flow as built-in
settings; only mounted plugins that declare properties appear. If no plugin
setting appears, the plugin may still be mounted.

![Configuration details](images/admin/10-configs-detail.png)

`gateway.loadbalance.strategy` is a startup/plugin-mounting setting and is not
edited from Admin. Configure a built-in strategy, SPI `name()`, or implementation
FQCN in `rover-gateway.yml`, then restart Gateway. `gateway.trace.sampleRate`
accepts a decimal from 0 to 1; `0` records only slow requests and `1` records
all requests. Window values use seconds, while most timeouts and health settings
use milliseconds.

## Lightweight usage

- Live data is polled while the dashboard is visible; background tabs and other
  pages avoid the high-frequency live request.
- Admin talks to component management APIs, not business services.
- Restrict port `9090` to the operations network; do not expose Admin publicly.
