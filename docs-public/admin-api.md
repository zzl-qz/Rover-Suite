# Rover-Admin API

Admin API is same-origin with the console at `http://127.0.0.1:9090` and all paths use the `/api`
prefix. Admin is a static console plus an aggregation layer; it stores no business data of its own and
calls the Gateway and Nameserver manage APIs.

Console pages and `/api/*` are protected by Spring Security: when not signed in, pages redirect to
`/login.html` and API calls return `401`. Sign-in is enabled only when
`rover.admin.auth.password-hash` (BCrypt, recommended) or `rover.admin.auth.password` (plain text,
hashed into memory at startup) is set. When both are empty there is no authentication at all — that is
acceptable for loopback debugging only: startup logs a WARN and the console shows a hint banner.

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
| GET | `/api/instances` | Nameserver registered instances |
| GET | `/api/nameserver/metrics` | Nameserver metric snapshot |
| GET | `/api/events` | Recent Nameserver events |
| GET | `/api/traces?traceId=&path=&slow=` | Gateway request traces and filters |
| GET | `/api/configs` | Gateway/Nameserver configuration metadata |
| POST | `/api/configs` | Update one configuration entry |
| GET | `/api/agent/sessions` | Sessions owned by the current user |
| POST | `/api/agent/sessions` | Create a session; body `{"title":"..."}`, title optional |
| GET | `/api/agent/sessions/{sessionId}` | Session detail: session + conversation + active incident |
| POST | `/api/agent/sessions/{sessionId}/messages` | Send a message (a new question or a follow-up on the active incident) |
| GET | `/api/agent/tasks/{taskId}` | Task detail: steps, evidence, conclusion |
| GET | `/api/agent/incidents/{incidentId}` | Incident detail: target, time range, latest conclusion |
| POST | `/api/agent/diagnoses` | **Deprecated**: one-shot diagnosis; forwarded internally to the agent orchestrator. Body `{"path":"/api/demo/tt","question":"why does it fail?"}` |
| GET | `/api/agent/diagnoses/{taskId}` | Query task status, collection and interpretation steps, conclusion, evidence |
| GET | `/api/agent/diagnoses/{taskId}/stream` | Subscribe to "AI interpretation" deltas (SSE): replay `snapshot`, then `delta`, then a final `end` |
| GET | `/api/auth/status` | Sign-in state and CSRF token; public |
| POST | `/login` | Form sign-in (`username`, `password`, `_csrf`); on success 302 to `redirect` or `/`, on failure 302 to `/login.html?error=1` |
| POST | `/api/logout` | Sign out; returns 200. POST only |
| GET | `/api/model/config` | Current model configuration, live state, and presets |
| POST | `/api/model/config` | Save model configuration and apply it immediately (no Admin restart) |
| POST | `/api/model/test` | Connectivity test with the submitted candidate values; nothing is persisted |
| POST | `/api/model/verify` | Effect verification against the currently applied configuration |

Diagnosis tasks are `PENDING`, `RUNNING`, `COMPLETED`, or `FAILED`. Results contain `summary`,
`confidence`, `evidence` with source and collection time, `limitations`, and hypothesis checks
`hypotheses` (each with `id`, `statement`, `status`, `detail`, `sources`, where `status` is
`CONFIRMED`, `REJECTED`, or `UNKNOWN`), plus an optional `aiAnalysis` once a model is configured.
Diagnosis is read-only: it never changes routes or configuration. Tasks live in Admin memory and
cannot be queried after a restart.

The AI interpretation is an optional live output. `GET /api/agent/diagnoses/{taskId}/stream` serves it
as `text/event-stream`: on connect it replays the text produced so far as `snapshot` (possibly empty,
which is how a reconnecting client aligns its prefix), then streams deltas as `delta`, and sends one
final `end` before closing; an unknown task returns `404`. The stream carries interpretation text only —
task status and collection steps still come from `GET /api/agent/diagnoses/{taskId}`. The connection
idles out after 180 seconds; neither a timeout nor a dropped connection affects the investigation, and a
reconnect replays the full text, so nothing is lost. When no model is configured or the model is
unavailable the task degrades directly and the subscriber gets an empty snapshot followed immediately by
`end`.

## Agent Workbench API

`POST /api/agent/diagnoses` is the one-shot diagnosis entry point and is kept for compatibility only: the
`AgentOrchestrator` internally turns it into "one session + one incident + one task", so the returned
`taskId` still works with `GET /api/agent/diagnoses/{taskId}`. New front ends use the conversational
endpoints below.

### Sessions and messages

- `POST /api/agent/sessions`: creates a session and returns a `Session` (`sessionId`, `userId`, `title`,
  `activeIncidentId`, `status`, timestamps, `incidentIds`). An empty title is derived from the first question.
- `GET /api/agent/sessions`: returns the **current user's** sessions in creation order.
- `GET /api/agent/sessions/{sessionId}`: returns `{"session":...,"messages":[...],"activeIncident":...}`.
  `activeIncident` is `null` when the session has no incident; a session owned by someone else returns `404`.
- `POST /api/agent/sessions/{sessionId}/messages`: only `message` is required:

| Field | Description |
| --- | --- |
| `message` | This turn's question, required; natural language such as "why does /api/demo/tt fail?" is supported |
| `path` / `service` / `instance` | Optional manual target from the collapsible "advanced context"; mutually exclusive, priority path > service > instance |
| `fromMillis` / `toMillis` | Optional time range (epoch millis); either both or neither, as a valid closed interval, otherwise `400` |

The response is an `AgentResponse`: `session`, `incident`, `task`, `reply`, `clarification`. `task` and
`clarification` are mutually exclusive — either an investigation has started (`task` non-null) or the target
could not be determined and the user must supply more detail (`clarification` non-null, in which case nothing
is guessed and no task is created). `reply` is the agent message already written to the session and can be
appended to the conversation directly. A full task queue returns `429` with
`{"message":"调查任务繁忙，请稍后再试"}`.

### Context and follow-ups

Later messages in a session are not independent questions: the orchestrator carries the **N most recent
messages** (`rover.agent.context.recent-message-limit`, default 8) + the **active incident context** + the
**current structured target** + the incident's **key evidence**, so follow-ups like "why are there no
instances?", "what about yesterday?", or "just that service from before" stay on the same incident. Incident
reuse is **same target ⇒ reuse the active incident** (an explicit time range in the request updates that
incident's range); a clearly different target opens a new incident and makes it the session's active one.
When no new target can be resolved, reusing the active incident's target is conversation continuity rather
than guessing; `clarification` is returned only when there is genuinely nothing to inherit, and no task is
created in that case. An incident's `summary` and `status` aggregate the latest round: attaching a new
investigation moves it to `INVESTIGATING`, producing a conclusion moves it to `RESOLVED` and updates the summary.

An empty `message`, or one longer than 1000 characters, returns `400`.
`GET /api/agent/tasks/{taskId}` and `GET /api/agent/incidents/{incidentId}` return records owned by the
current user only, otherwise `404`.

### User identity

`userId` always comes from the backend authentication context (`Authentication.getName()`) and is empty when
sign-in is disabled or the caller is anonymous. There is no `userId` field in the request bodies and one is
not accepted; a same-named JSON field submitted by the front end is ignored. Session, task, and incident
lookups are filtered by that identity, so by default a user sees only their own records.

### Call examples

```bash
# Create a session
sessionId=$(curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{}' http://127.0.0.1:9090/api/agent/sessions | sed -E 's/.*"sessionId":"([^"]*)".*/\1/')

# Ask (natural language)
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"why does /api/demo/tt fail?"}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages"

# Follow up (reuses the active incident; a time range may be supplied)
curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
  -d '{"message":"what about yesterday?","fromMillis":1735689600000,"toMillis":1735776000000}' \
  "http://127.0.0.1:9090/api/agent/sessions/$sessionId/messages"

# Session detail / task detail
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/sessions/$sessionId"
curl -s -b "$jar" "http://127.0.0.1:9090/api/agent/tasks/<taskId>"
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
- After `rover.admin.auth.max-login-failures` consecutive failures from the same source within
  `rover.admin.auth.failure-window-seconds`, `POST /login` returns `429` directly; a successful sign-in
  clears that source's counter. Neither success nor failure distinguishes "unknown user" from "wrong
  password"; failures always redirect to `/login.html?error=1`.
- The session cookie is `ROVERADMIN_SESSION` (HttpOnly, SameSite=Strict) and its timeout comes from
  `server.servlet.session.timeout` (30 minutes by default; use `sessionTimeoutSeconds` from
  `GET /api/auth/status` as the authoritative value). A successful sign-in changes the session id.
- Sign-out is `POST /api/logout` only: a GET sign-out cannot be CSRF protected, so a malicious page
  could use the browser to kick the operator offline.
- The SSE endpoint `GET /api/agent/diagnoses/{taskId}/stream` also returns `401` when not signed in. A
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

Subscribe to a task's AI interpretation deltas (`-N` disables curl buffering so chunks arrive live;
without a session this returns `401`):

```bash
curl -N -b "$jar" "http://127.0.0.1:9090/api/agent/diagnoses/<taskId>/stream"
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
| `presets` | Built-in presets (OpenAI, DeepSeek, Alibaba Cloud Bailian, Zhipu, local Ollama, local vLLM) that fill the console dropdown |

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
- When a downstream component is unreachable, authentication fails, or validation rejects the input,
  Admin returns the corresponding HTTP error status and the console shows a failure message.
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