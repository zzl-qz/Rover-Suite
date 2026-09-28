# Rover-Admin API

Admin API is same-origin with the console at `http://127.0.0.1:9090` and all paths use the `/api`
prefix. Admin is a static console plus an aggregation layer; it stores no business data of its own and
calls the Gateway and Nameserver manage APIs.

Console pages and `/api/*` require sign-in: pages redirect to `/login.html` and API calls return
`401`. The account is the `admin_user` row in the record store. The default username and password are
both `admin`, and everyone shares that one account.

`rover.admin.admin-token` is unrelated to console sign-in: it is only sent as `X-Rover-Admin-Token`
when Admin calls Gateway and Nameserver. In production, enable sign-in as well and keep port 9090
restricted by bind address, firewall, reverse proxy, or VPN.

## Endpoint catalogue

| Method | Path | Description |
| --- | --- | --- |
| GET | `/api/overview` | Gateway/Nameserver status, discovery mode, metric self-consistency checks |
| GET | `/api/live?range=60\|300` | Lightweight live snapshot polled by the dashboard |
| GET | `/api/routes` | Gateway route list |
| POST | `/api/routes` | Create or update a route; body is the route object |
| DELETE | `/api/routes?businessPrefix=/api/demo` | Delete a route by business prefix |
| GET | `/api/routes/operations/{operationId}` | Route write-operation record: after a write times out, reuse the same `operationId` to confirm whether it was applied (`APPLIED` / `CONFLICT` / `REJECTED` / `FAILED` / `UNKNOWN`) |
| GET | `/api/instances` | Nameserver registered instances |
| GET | `/api/nameserver/metrics` | Nameserver metric snapshot |
| GET | `/api/events` | Recent Nameserver events |
| GET | `/api/traces?traceId=&path=&slow=` | Gateway request traces and filters |
| GET | `/api/configs` | Gateway/Nameserver configuration metadata |
| POST | `/api/configs` | Update one configuration entry |
| GET | `/api/agent/sessions` | Sessions owned by the current user |
| POST | `/api/agent/sessions` | Create a session; body `{"title":"..."}`, title optional |
| GET | `/api/agent/sessions/{sessionId}/workspace` | Workbench aggregate: session + conversation + incidents + recent tasks (`limit` default 20, max 100) |
| POST | `/api/agent/sessions/{sessionId}/messages` | Send a message (a new question or a follow-up on the active incident); `202` returns a task handle |
| GET | `/api/agent/tasks/{taskId}` | Task detail: steps, evidence, conclusion |
| GET | `/api/agent/tasks/{taskId}/events` | Task event stream (SSE): replay `SNAPSHOT`, then push structured events |
| POST | `/api/agent/tasks/{taskId}/cancel` | Cooperative cancel of a task that is still running: 404 when the task is unknown or not owned, 409 `TASK_NOT_CANCELLABLE` when it has finished, 200 once it is marked cancelled and its thread is interrupted |
| POST | `/api/agent/events/ingest` | Event ingestion: turn one alert into an automatic investigation (route + window + suspicion); `202` returns a task handle |
| GET | `/api/auth/status` | Sign-in state and CSRF token; public |
| POST | `/login` | Form sign-in (`username`, `password`, `_csrf`); on success 302 to `redirect` or `/`, on failure 302 to `/login.html?error=1` |
| POST | `/api/logout` | Sign out; returns 200. POST only |
| GET | `/api/model/config` | Current model configuration, live state, and presets |
| POST | `/api/model/config` | Save model configuration and apply it immediately (no Admin restart) |
| POST | `/api/model/test` | Connectivity test with the submitted candidate values; nothing is persisted |
| POST | `/api/model/verify` | Effect verification against the currently applied configuration |

Agent tasks are `PENDING`, `RUNNING`, `WAITING_INPUT`, `COMPLETED`, or `FAILED` (`CANCELLED` is
reserved by the protocol but has no entry point yet). `WAITING_INPUT` means the target could not be
determined from the question and session context: the task stops at the clarification point, which does not
count as running and does not hold the session's concurrency slot; the question to answer is in the task's
`clarification` field, and the session's next task continues once the user replies. Results contain `summary`,
`confidence`, `evidence` with source and collection time, `limitations`, and hypothesis checks
`hypotheses` (each with `id`, `statement`, `status`, `detail`, `sources`, where `status` is
`CONFIRMED`, `REJECTED`, or `UNKNOWN`), plus an optional `aiAnalysis` once a model is configured.
Diagnosis is read-only: it never changes routes or configuration. Tasks live in Admin memory and
cannot be queried after a restart.

Besides `source`, `observedAtMillis`, and `rawReference`, every piece of evidence carries its statistics in
`metadata`: `windowSeconds` (`0` means a point-in-time snapshot), `sampleSize`, and the resource dimension
(`routeId` / `hostPort` / `component`). When the sample is too small the answer says so instead of claiming
recovery from an empty sample. The hypothesis set includes "one upstream instance of this route returned 5xx":
`CONFIRMED` (naming the instance) only when that instance has at least the threshold of 5 in-window samples and
real 5xx among them; `REJECTED` when the sample is adequate and 5xx-free; `UNKNOWN` when the window holds no
forwarded request at all, explicitly noting that this cannot be read as recovery.

`GET /api/agent/tasks/{taskId}` and SSE snapshots carry a `taskType` with exactly two values, one per real
execution path:

- `CONVERSATION`: a human session. The model decides by itself which read-only tools to call and how many times,
  so no plan is produced up front (`plan` stays empty, `executedCapabilities` still lists what actually ran).
- `INVESTIGATION`: an investigation triggered by machine events such as alerts, carrying `plan`
  (`goal`, `hypotheses`, `steps`; each step has `capability`, `reason`, `target`, `required`) and
  `executedCapabilities` (capabilities actually called or skipped on facts).

`taskType` is the shape users see, so it always matches what actually happened. There is no `intent` field: the main
path performs no intent pre-classification — which of querying, explaining, or investigating a sentence means is
decided by the model during the conversation, so there is no verdict to display. There is no action plan either:
no write capability exists in the catalogue, and an `ACTION_PLAN` shape never existed.

The task event stream `GET /api/agent/tasks/{taskId}/events` serves every structured event of a task as
`text/event-stream`: the SSE event name is the event type and the data is the full event envelope
(`eventId`, `taskId`, `type`, `timestampMillis`, `payload`). On connect it replays one `SNAPSHOT`
(task view + interpretation text produced so far + `coveredEventId`), then pushes increments such as
`TASK_STARTED`, `STEP_STARTED`, `STEP_COMPLETED`, `STEP_FAILED`, `EVIDENCE_ADDED`, `ANALYSIS_DELTA`, and
`CLARIFICATION_REQUIRED`; it finishes and closes once the task reaches a terminal state
(`TASK_COMPLETED`/`TASK_FAILED`/`TASK_CANCELLED`) or stops at the clarification point. Investigation plans and
capability progress are results rather than process logs: they are
pushed as full `SNAPSHOT` events (overwrite semantics), so a client joining late or reconnecting can always
reconstruct "what the plan was and what actually ran".
`GET /api/agent/tasks/{taskId}`
remains the source of truth: everything an event carries must be queryable there, and lost events or dropped
connections never affect the investigation. The connection idles out after 180 seconds; a reconnect replays the
snapshot, so nothing is lost. An unknown, no-longer-observable, or not-owned task returns `404`.

## Agent Workbench API

### Sessions and messages

- `POST /api/agent/sessions`: creates a session and returns a `Session` (`sessionId`, `userId`, `title`,
  `activeIncidentId`, `status`, timestamps, `incidentIds`). An empty title is derived from the first question.
- `GET /api/agent/sessions`: returns the **current user's** sessions in creation order.
- `POST /api/agent/sessions/{sessionId}/messages`: only `message` is required:

| Field | Description |
| --- | --- |
| `message` | This turn's question, required; natural language such as "why does /api/demo/tt fail?" is supported |
| `path` / `service` / `instance` | Optional manual target from the collapsible "advanced context"; mutually exclusive, priority path > service > instance |
| `fromMillis` / `toMillis` | Optional time range (epoch millis); either both or neither, as a valid closed interval, otherwise `400` |

The response is `202 Accepted` + `{"sessionId":...,"taskId":...,"status":"PENDING"}`: submission only
validates and registers; target resolution and investigation run on a background worker, so this endpoint is
never blocked by model calls. Progress is observed through `GET /api/agent/tasks/{taskId}` and the task event stream.

- A task already running in the same session returns `409` +
  `{"code":"SESSION_TASK_RUNNING","message":...,"taskId":"<running task>"}`: one session allows only one
  active investigation at a time.
- A full task capacity or execution queue returns `429` +
  `{"code":"TASK_BUSY","message":"调查任务繁忙，请稍后再试"}`.
- When the target cannot be determined nothing is guessed and no incident is opened: the task simply carries an
  empty `incidentId`, and the model still answers against the global scope. Only **alert investigations** stop at
  `WAITING_INPUT`, with `clarification` holding the question to answer; the session's next task continues once the
  user replies (this stage does not resume the same task in place).

Supported phrasings and where they land (every human question takes the same path — the model decides what to query
and how to answer; the table lists what actually happens):

| Example | Where it lands |
| --- | --- |
| `网关 QPS 多少？` | Conversation: the model calls `GATEWAY_METRICS_QUERY` and answers from the window facts; three questions in one sentence mean three calls |
| `order-service 几个健康实例？` | Conversation: the model calls `INSTANCE_QUERY` for the healthy-instance count of the resolved service |
| `/api/demo/tt 命中了哪条路由？` | Conversation: the model calls `ROUTE_QUERY` and answers the matched route facts |
| `为什么 /api/demo/tt 调用失败？` | Conversation: the model decides which facts to query (route → instances → metrics → traces) and composes the answer; a resolved target groups the task under an incident |
| `你能做什么？` | Conversation: the model answers from the real capability set (route / instance / gateway metrics / trace / configuration / registry events / historical logs / operations knowledge) |
| `你好` / chit-chat | Conversation: nothing is queried; no "please provide a request path" clarification |
| `把 order-03 摘掉` | Read-only boundary: no write capability exists, so the model can only return read-only facts and rationale instead of a pretend-executable remediation plan |
| `每天 9 点自动巡检并发邮件` | Capability boundary: scheduling and notification are not wired up; says so plainly |

Planning limits (rounds / capability calls / steps per round) are documented in `configuration-reference`;
when a limit is reached the limitation is written into `limitations` and skipped steps are reported honestly.

### Workbench aggregate

`GET /api/agent/sessions/{sessionId}/workspace?limit=20` returns
`{"session":...,"messages":[...],"incidents":[...],"tasks":[...],"activeIncident":...}` in one call, so the
front end does not issue one request per task. `limit` only caps the number of tasks (default 20, max 100) and
`tasks` is ordered newest first; full task detail is still fetched on demand from `GET /api/agent/tasks/{taskId}`.
A session owned by someone else returns `404`.

### Context and follow-ups

Later messages in a session are not independent questions: the orchestrator carries the **N most recent
messages** (`rover.agent.context.recent-message-limit`, default 8) + the **active incident context** + the
**current structured target** + the incident's **key evidence**, so follow-ups like "why are there no
instances?", "what about yesterday?", or "just that service from before" stay on the same incident. Incident
reuse is **same target ⇒ reuse the active incident** (an explicit time range in the request updates that
incident's range); a clearly different target opens a new incident and makes it the session's active one.
When no new target can be resolved, reusing the active incident's target is conversation continuity rather
than guessing; only when there is genuinely nothing to inherit does the task stop at `WAITING_INPUT` for the
user to supply details, without opening an incident or starting an investigation. An incident's `summary` and `status` aggregate the latest round: attaching a new
investigation moves it to `INVESTIGATING`, producing a conclusion moves it to `RESOLVED` and updates the summary.

An empty `message`, or one longer than 1000 characters, returns `400`.
`GET /api/agent/tasks/{taskId}` returns records owned by the current user only, otherwise `404`.

### User identity

`userId` always comes from the backend authentication context (`Authentication.getName()`) and is empty when
sign-in is disabled or the caller is anonymous. There is no `userId` field in the request bodies and one is
not accepted; a same-named JSON field submitted by the front end is ignored. Session, task, and incident
lookups are filtered by that identity, so by default a user sees only their own records.

### Event ingestion (alert → automatic investigation)

`POST /api/agent/events/ingest` is the machine-to-machine entry point for monitoring systems: it turns one alert into
"which route + which window + what is suspected", Admin opens a dedicated session for it, records an `ALERT`-sourced
incident, and runs the investigation over exactly the same read path a human question would use.

| Field | Required | Meaning |
| --- | --- | --- |
| `path` | yes | Route prefix to investigate, e.g. `/api/demo/tt`; missing or blank returns `400` |
| `message` | no | Alert text (symptoms and suspicion) |
| `fromMillis` / `toMillis` | no | Observation window (epoch millis); they must come as a pair, supplying only one returns `400` |

- Response is `202` plus a task view (`sessionId`, `taskId`, `status`); the caller polls task detail or subscribes to
  SSE with that `taskId`, exactly as for a human question.
- Sign-in is required (a dedicated service account is recommended) and the session it creates is **owned by no human
  user**, so it never competes with human sessions for the "one active task" lock and can be aggregated separately in
  the incident view. Sessions and tasks still live in Admin memory and disappear on restart, so callers should keep
  their own copy of the `taskId` and the conclusion.
- The agent never pulls from the monitoring source on its own: proactive inspection (periodic self-inspection raising
  `INSPECTION` incidents) is not implemented, so pushing remains the monitoring system's job.

```bash
curl -s -X POST -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"path":"/api/demo/tt","message":"5xx spiking","fromMillis":1735689600000,"toMillis":1735776000000}' \
  "http://127.0.0.1:9090/api/agent/events/ingest"
```

### Call examples

```bash
# Create a session
sessionId=$(curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{}' http://127.0.0.1:9090/api/agent/sessions | sed -E 's/.*"sessionId":"([^"]*)".*/\1/')

# Ask (natural language); 202 returns {"sessionId":...,"taskId":...,"status":"PENDING"}
taskId=$(curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"why does /api/demo/tt fail?"}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages" | sed -E 's/.*"taskId":"([^"]*)".*/\1/')

# Follow up (reuses the active incident; a time range may be supplied)
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"what about yesterday?","fromMillis":1735689600000,"toMillis":1735776000000}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages"

# Workbench aggregate / task detail / task event stream
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/sessions/$sessionId/workspace"
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>"
curl -N -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>/events"
```

## Request examples

Read a one-minute dashboard window:

```bash
curl "http://127.0.0.1:9090/api/live?range=60"
```

Create or update a route:

```bash
curl -X POST "http://127.0.0.1:9090/api/routes" \
  -H "Content-Type: application/json" \
  -d '{"id":"demo-api","businessPrefix":"/api","serviceName":"demo-service"}'
```

Update a configuration entry:

```bash
curl -X POST "http://127.0.0.1:9090/api/configs" \
  -H "Content-Type: application/json" \
  -d '{"component":"gateway","key":"gateway.trace.sampleRate","value":"1"}'
```

Route bodies must match the Gateway route model. For configuration updates `component` is either
`gateway` or `nameserver`, and `key`/`value` are validated again by the downstream component.
`gateway.loadbalance.strategy` belongs to startup/plugin wiring and is not changed through the Admin API.

## Authentication, session, and CSRF

- Public paths: `/login.html`, `POST /login`, `/api/auth/status`, and static assets `/css/**`,
  `/js/**`, `/vendor/**`, `/images/**`, `/favicon.svg`, `/error`.
- When not signed in: `/api/*` returns `401` with `{"code":401,"message":"请先登录控制台"}`, while page
  requests are redirected to `/login.html`.
- Write requests (POST/PUT/DELETE) must send the CSRF header `X-XSRF-TOKEN`, whose value comes from the
  `XSRF-TOKEN` cookie (`GET /api/auth/status` seeds that cookie and also returns a `csrfToken` field).
  A missing or mismatched token returns `403`.
- Spring Security **rotates** the CSRF token after a successful sign-in, so callers should read the token
  from the cookie for every write request instead of caching it.
- A failed sign-in always redirects to `/login.html?error=1`. The default username and password are
  both `admin`.
- The session cookie is `ROVERADMIN_SESSION` (HttpOnly, SameSite=Strict) and its timeout comes from
  `server.servlet.session.timeout` (30 minutes by default; use `sessionTimeoutSeconds` from
  `GET /api/auth/status` as the authoritative value). A successful sign-in changes the session id.
- Sign-out is `POST /api/logout` only: a GET sign-out cannot be CSRF protected, so a malicious page
  could use the browser to kick the operator offline.
- The SSE endpoint `GET /api/agent/tasks/{taskId}/events` also returns `401` when not signed in. A
  browser `EventSource` cannot attach custom headers, so it authenticates with the session cookie only;
  the endpoint is a GET and needs no CSRF header, and the connection dies with the session on sign-out.

Scripted calls with a signed-in session:

```bash
jar=$(mktemp)
token=$(curl -s -c "$jar" http://127.0.0.1:9090/api/auth/status | sed -E 's/.*"csrfToken":"([^"]*)".*/\1/')
curl -s -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}\n' \
  -d "username=admin&password=<your-password>&_csrf=$token" http://127.0.0.1:9090/login
# The token is rotated after sign-in; read it again before writing
token=$(curl -s -b "$jar" -c "$jar" http://127.0.0.1:9090/api/auth/status | sed -E 's/.*"csrfToken":"([^"]*)".*/\1/')
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" http://127.0.0.1:9090/api/model/config
```

Subscribe to a task's event stream (`-N` disables curl buffering so chunks arrive live;
without a session this returns `401`):

```bash
curl -N -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>/events"
```

## Model configuration API

`GET /api/model/config` returns the current configuration, live state, and presets:

| Field | Description |
| --- | --- |
| `enabled` / `baseUrl` / `model` / `timeoutSeconds` | Currently saved values; the timeout is clamped to 1–300 seconds |
| `source` | `FILE` (saved from the console), `ENV` (seeded from environment variables, no file yet), `NONE` (not configured) |
| `configured` | Enabled with a non-empty base URL and model name — "has been configured" |
| `available` | A usable model client has been built — "usable right now"; a separate question from `configured` |
| `applied` | Whether the applied configuration matches the saved one; `false` means it needs saving again |
| `description` / `buildId` / `appliedAt` / `lastError` | Applied model description, build version, applied time, and most recent error |
| `apiKeyMasked` | Either `******` or empty; plain text and cipher text never appear in any response |
| `apiKeyReadable` / `masterKeyState` | Whether the key can be decrypted; `MISMATCH` means the key must be entered again in the console |
| `configFile` | Absolute path of the configuration file, for backup and troubleshooting |
| `presets` | Built-in presets (DeepSeek, Zhipu GLM) that fill the console dropdown |

`POST /api/model/config` accepts the same field names:

- No `apiKey` field, or `******`: keep the stored key. Only `"clearApiKey":true` clears it (for local
  models without authentication).
- `enabled=false` only disables model capability while keeping the configuration; when enabled, both
  `baseUrl` and `model` are required, and the URL must start with `http://` or `https://`.
- A successful save applies the new client immediately and also returns `message` ("model configuration
  applied, no Admin restart needed", or "saved, but currently unavailable").

`POST /api/model/test` and `POST /api/model/verify` both return `ok`, `latencyMs` (milliseconds, `-1` on
failure), `errorCode`, `message`, `model`, `baseUrlHost`; `verify` additionally returns `buildId` and
`appliedAt`. `errorCode` is one of `AUTH`, `PERMISSION`, `QUOTA`, `MODEL_NOT_FOUND`, `BAD_REQUEST`,
`NETWORK`, `SERVER`, `TIMEOUT`, `UNKNOWN`, plus `NOT_CONFIGURED` and `UNAVAILABLE` for `verify`. The
difference: `test` probes the candidate values in the request body without persisting them and without
affecting the applied configuration, while `verify` runs against the configuration currently in effect,
proving that what is applied is exactly what was saved.

## Responses and errors

- Successful responses are JSON. A configuration update returns at least `component`, `key`, and
  `message`, and some entries include `payload`.
- Unauthenticated `/api/*` calls return `401` with `{"code":401,"message":"请先登录控制台"}`; a write
  request without the CSRF header returns `403`; `POST /login` returns `429` once the failure limit is hit.
- When a downstream component answers with a client error, Admin passes the status code and the original
  message through unchanged (for example a route revision conflict stays `409` with its own text). Only
  when no downstream response is obtained at all (connection failure or timeout) does Admin return
  `502` with a generic message, and it logs a WARN.
- When an older component lacks metric endpoints, aggregation endpoints return structured JSON with an
  `error` field instead of breaking the whole dashboard.
- Never write `X-Rover-Admin-Token`, protocol tokens, cookies, or full request bodies to logs.

## Lightweight usage notes

- `/api/live` polls roughly once per second only while the dashboard is visible; background tabs and
  other pages do not keep pulling full snapshots.
- `/api/overview` suits low-frequency liveness checks; do not use it as a high-frequency collector.
- `/api/traces` volume is bounded by the Gateway sample rate and ring buffer; for slow-request
  investigations prefer `slow=1` rather than leaving full sampling on.
- Refresh lists only after a write succeeds, to avoid submitting the same route or configuration twice.

The Admin API is a same-version control-plane surface. Clients should depend only on the paths,
parameters, and fields listed here, not on undocumented internal aggregation fields; upgrade Gateway,
Nameserver, and Admin together.