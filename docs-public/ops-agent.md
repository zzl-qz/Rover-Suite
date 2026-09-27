# Rover Ops Agent / Incident Investigation & Controlled Remediation Agent

> **A gateway-centric AIOps agent that investigates microservice faults and performs controlled remediation.**
>
> 面向网关与微服务运行态的 AIOps 故障调查与受控处置 Agent

This document is the product-positioning anchor for the Rover Ops Agent module. Scope decisions, priorities, and
architecture choices for this module follow this document.

> **Documentation convention:** public docs describe only behavior that exists in the released code. Every section below
> is marked *Implemented* or *Planned*. Planned items are not current capability.

## 1. What it is, and what it is not

When an engineer starts troubleshooting, or when monitoring raises an alert, the agent collects gateway route,
service instance, metric, and trace evidence to complete fault investigation, root-cause inference, and remediation
recommendations. When policy and approval allow, it may also perform limited operational actions and verify the result.

Two positions are explicitly rejected:

| Not this | Why |
| :--- | :--- |
| A "chat bot inside the gateway" | Conversation is only the interface; the value is an evidence-backed investigation chain over real runtime state |
| An "automated alerting bot" | Deterministic threshold decisions belong to monitoring; polling metrics with an LLM is expensive and unstable |

## 2. Boundary with monitoring systems

```text
Monitoring / alerting (Prometheus, AlertManager, Rover Metrics)
        Detects "something is wrong" - deterministic rules
                    ↓
            Alert / Event
                    ↓
              Rover Ops Agent
        Investigates "why it is wrong" - evidence and root cause
                    ↓
            Incident Report
```

**Monitoring detects the problem; the agent investigates the problem.** Neither replaces the other.

监控系统的告警可通过 `POST /api/agent/events/ingest` 直接驱动一次自动根因排查（记 `ALERT` 来源事件），从而闭合
「监控发现问题 → Agent 调查原因」的回路；告警仍由监控系统判定的「有事发生」，Agent 负责「为什么发生」。

## 3. Target capability chain

```text
Detect → Investigate → Correlate → Diagnose → Recommend → Approve → Execute → Verify
```

| Stage | Owner | Status |
| :--- | :--- | :--- |
| Detect | Monitoring / alerting, and future event ingestion | Planned |
| Investigate → Diagnose | **Core value of the agent** | In progress (read-only chain orchestrated by a graph, with hypothesis verification) |
| Recommend | Agent | In progress (produces non-executable action plans) |
| Approve / Execute / Verify | Agent, with a governance layer | Planned |

## 4. Capability levels

### Level A: Observe

The agent queries Route, Instance, Metrics, Trace, Config, Events, Logs (historical logs), and Knowledge (operational
knowledge) runtime data. Read-only.

**Status: implemented.** `InvestigationService` (in `rover-agent-runtime`), driven by `AgentOrchestrator`, collects
route, instance, metric, trace, configuration, registry-event, historical-log, and operational-knowledge snapshots and
returns a conclusion, evidence sources, and collection timestamps. Metrics go beyond the global window: they also expose
windowed request counts, status codes, error rate, and latency per route × upstream instance, which is what names the
instance that is failing. Historical logs are filtered by type / target / time range (config change, rollback, error,
instance up/down, metric sample, slow or error trace), and operational knowledge answers "how to configure / onboard /
troubleshoot" questions.

### Level B: Reason

The agent builds an investigation plan, selects tools dynamically, correlates evidence, eliminates wrong hypotheses,
and produces a root cause with remediation advice.

**Status: in progress.** Hypothesis-driven investigation is live for the read-only chain: the investigation runs as a
StateGraph where a planner proposes read-only steps (a plan → execute → evaluate loop) and a synthesis node confirms or
eliminates candidate causes one by one (for example "the path matches no route", "the target service has no matching instance",
"all matching instances are unhealthy", "downstream calls failed recently", "one upstream instance of this route returned 5xx"),
each bound to its evidence sources.

Dynamic capability selection and evidence-adaptive investigation plans are now live: the orchestrator first decides the
intent, then selects read-only capabilities from the capability registry; a planner produces the plan, an executor runs it,
and an evaluate node decides whether to keep planning, ask for clarification, or conclude. The three hard limits — planning
rounds, capability calls, and plan steps — are enforced by code rather than the prompt; hitting them stops collection and
is stated plainly in the conclusion. Action requests only produce a non-executable action plan (`executable` is always
`false`); execution is deferred to Level C.

### Level C: Act

With authorization, the agent performs limited operational actions such as draining an unhealthy instance, adjusting
routes, changing rate limits, or triggering a rollback.

**Status: planned.** This level requires policy, RBAC, risk grading, human approval, idempotency, rollback, and result
verification.

## 5. Two entry points

```text
Engineer-initiated (implemented)     Event / alert triggered (planned)
        │                              │
        └──────────┬───────────────────┘
                   ↓
         Intent decision
                   │
     ┌─────────────┼──────────────┬──────────────┬─────────────┐
     ↓             ↓              ↓              ↓             ↓
  QUERY_STATE  INVESTIGATE     EXPLAIN     ACTION_REQUEST  unsupported
(few read-only  (dynamic     (capability   (non-executable  (answered
 capabilities) investigation)  summary)        plan)        honestly)
                   ↓
             Incident Task
                   ↓
   Investigation graph: PLAN → EXECUTE → EVALUATE (loops on evidence, stops at limits)
                   ↓
   synthesise (confirm / eliminate / unverifiable → conclusion)
                   ↓
   Events / Logs / Change / SOP (planned)
                   ↓
             Root Cause
                   ↓
            Action Plan (produced, not executed)
```

State queries, capability questions, and action plans create no incident; only fault investigations land under one.

- **Engineer-initiated:** ask in one sentence on the Admin Agent Workbench (e.g. "why does /api/demo/tt fail?"), with an
  optional collapsible "advanced context" for route / service / instance and a time range; follow-ups reuse the active
  incident. **Implemented.**
- **Alert / event initiated:** an Alert or Gateway event creates an Incident and starts the investigation automatically. **Planned.**

## 6. Architecture choices

| Decision | Choice | Rationale |
| :--- | :--- | :--- |
| Orchestration | One agent + investigation graph (Spring AI Alibaba StateGraph) + investigation nodes | Ops tasks have conditional branches, long-running steps, human approval, and resumable state; a single ReAct loop does not fit |
| Multi-agent | Not yet | Metrics / Logs / Trace are steps of one incident investigation; split into sub-agents only when context, permissions, model, or lifecycle truly diverge |
| Model execution rights | The model holds none | Tool calls execute in the application; the model cannot reach management APIs directly |
| Tool governance | Built in-house | Tool schema validation, RBAC, resource scope, timeout/retry, audit, and human approval for high-risk actions |

**Orchestration dependency: `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` is adopted (2.0.0-M1.1, matching the
Spring AI 2.0.x line).** The read-only investigation chain is now a StateGraph:

```text
START → plan ──steps available──→ execute (runs read-only capabilities) → evaluate ──evidence enough──→ synthesise → END
          │                                                                  │
          └──no steps this round──────────────────────────────────────────────┤
                                                                             ├──insufficient & under limits──→ back to plan
                                                                             └──target unclear──────────────→ clarify → END
```

- State holds only scalars (rounds, capability calls, whether steps are available, the verdict); evidence, limitations,
  the plan, and the conclusion live in graph instance fields and are never read back from the returned state.
- Planning may only choose capabilities registered as available read-only ones, and the executor is the single data
  access point: out-of-scope candidate steps are dropped and recorded as a limitation, so no step can be
  non-executable or over-privileged.
- The loop limits are enforced by code rather than the prompt: `rover.agent.planning.max-rounds` (default 3),
  `max-tool-calls` (default 10), `max-plan-steps` (default 6); hitting one stops collection and marks the boundary in the
  conclusion and step notes.
- One graph instance is built per investigation: the state returned by `invoke()` is a serialization snapshot of the
  framework, and nested collections or enums inside records degrade to `Map` / `List` when read back (reads and writes
  during node execution are fine). Therefore the conclusion is produced directly by the `synthesise` node and the
  step-progress callback is held by node closures; neither is read back from the returned state.

Checkpoint and interrupt are not enabled yet: read-only tasks are short and do not need recovery, so they are deferred
until the Level C approval and long-task phase.

## 6.1 Code layout and module boundaries

The agent is split into three layers with one-way dependencies:

```text
rover-admin (web contract + composition root + read-only adapters)
        ↓
rover-agent-runtime (Spring AI + StateGraph orchestration, task lifecycle, model interpretation)
        ↓
rover-agent-core (plain Java: domain objects, read-only ports, neutral snapshots, diagnosis rules; no Spring, no Jackson)
```

| Module | Package | Responsibility |
| :--- | :--- | :--- |
| `rover-agent-core` | `com.rover.agent.core.model` | Session / Incident / AgentMessage / Task / Step / Evidence / Hypothesis / InvestigationReport / TaskView / ResourceTarget / AgentIntent / IntentDecision / TaskType / ActionPlan |
| | `com.rover.agent.core.snapshot` | Neutral read-only snapshots: RouteSnapshot / InstanceSnapshot / GatewayMetricSnapshot / TraceSnapshot / DiscoveryMode |
| | `com.rover.agent.core.port` | Read-only ports: RouteReadPort / InstanceReadPort / MetricReadPort / TraceReadPort; unavailable data raises SnapshotUnavailableException |
| | `com.rover.agent.core.repository` | Storage interfaces: AgentSessionRepository / AgentMessageRepository / IncidentRepository / AgentTaskRepository (in-memory or persistent implementations are swappable) |
| | `com.rover.agent.core.context` | AgentContextManager (N most recent messages + active incident + structured target + key evidence); TargetResolver / ResourceTarget (explicit input → existing routes and instances → model assistance → clarification) |
| | `com.rover.agent.core.investigation` | RouteMatcher / EvidenceNarrator / InvestigationRules (pure functions, unit-testable without the framework) |
| | `com.rover.agent.core.intent` | IntentClassifier / IntentDecision / IntentService: intent recognition with constrained values (out-of-range values are dropped) |
| | `com.rover.agent.core.capability` | CapabilityDescriptor / CapabilityRegistry / CapabilityExecutor / CapabilityResult / AgentGrounding (environment profile and glossary) / UntrustedText (sentinel fencing for untrusted content): capability catalogue and the single read-only execution point |
| | `com.rover.agent.core.planning` | InvestigationPlanner / InvestigationPlan / PlannedStep / PlanValidator / PlanningLimits / RuleBasedPlanner: plan production, out-of-scope dropping, hard limits |
| `rover-agent-runtime` | `com.rover.agent.runtime` | AgentOrchestrator (application entry: context → intent → target → task → dispatch); InvestigationService (investigation task lifecycle); QueryStateService / ExplainService / ActionPlanService (state queries, capability summary, action plans) |
| | `com.rover.agent.runtime.graph` | DynamicInvestigationGraph: plan / execute / evaluate / clarify / synthesise nodes, looping conditional edges, conclusion synthesis |
| | `com.rover.agent.runtime.planning` | LlmInvestigationPlanner: rule-based baseline plus model candidates, out-of-scope steps dropped by PlanValidator |
| | `com.rover.agent.runtime.task` | Task lifecycle, Session / Incident registry (through the storage interfaces; in-memory and bounded today) |
| | `com.rover.agent.runtime.repository` | Four thread-safe in-memory implementations (lost on restart), replaced when persistence lands |
| | `com.rover.agent.runtime.tool` | SnapshotTools: exposes the snapshots collected in this run to the model |
| | `com.rover.agent.runtime.llm` | JsonCompletion (structured-output port; the parsing contract is supplied by the caller) / ModelExplainer (route and instance snapshots pre-read into the prompt, metrics / traces on demand through read-only tools) / LlmIntentInterpreter / ModelTargetInterpreter (constrained JSON output for intent and target) / SpringAiJsonCompletion (the implementation, which records each call's outcome against `ModelCallOutcome`) |
| `rover-admin` | `com.rover.admin.agent.adapter` | Four read-only adapters: AdminConfigService → ports, never triggering a write |
| | `com.rover.admin.agent` | AgentController (session / message / task / incident contract) + composition root |

**Boundary rules:**

- `rover-agent-core` depends on neither Spring, Jackson, nor Spring AI: the JSON shape of the Admin management API is
  parsed only in the `rover-admin` adapters, while core rules speak neutral snapshots. That keeps core directly
  unit-testable and data-source agnostic.
- Read-only is structural: the port interfaces only expose read methods, so the runtime cannot reach
  `saveRoute / deleteRoute / updateConfig`.
- Spring AI and Graph dependencies appear only in the `rover-agent-runtime` and `rover-admin` modules; Admin also owns
  the model-configuration wiring, so the agent can later move to its own process.
- The parent `spring-boot.version` stays at 3.2.0: `rover-agent-runtime` and `rover-admin` each import the
  Spring Boot 4.1.1 and Spring AI 2.0.1 BOMs inside their own module.

**Core object relationships:**

```text
Session (one continuous conversation) ─┬─ Incident (one problem under investigation; origin USER / ALERT / INSPECTION)
                                       └─ Task (one investigation run) ── Step (phase progress)
                                                                      └── Evidence (evidence source)
                                                                      └── Hypothesis (confirmed / eliminated / unverifiable)
```

Every question lands under a Session; only a fault investigation (INVESTIGATION) creates a `USER`-origin Incident and
hangs the Task off it — state queries, capability questions, and action plans create no incident (the TaskView carries
`sessionId` with an empty `incidentId`). **Follow-up questions work now**: later messages in a
session carry the N most recent messages, the active incident, the current structured target, and that incident's key
evidence; the active incident is reused when the target matches, a new incident opens only when a clearly different
target is resolved, and a clarification is returned rather than a guess when nothing can be resolved (only fault
investigations clarify on an unclear target; capability questions never enter target resolution). Session and
Incident live in memory only, so **everything is lost on restart**.

Approval policy and event ingestion for Level C will be split into further sub-packages as
they are built, rather than scaffolding empty modules now.

## 6.2 Model execution strategy

**Status: implemented.** The agent uses two classes of model calls, routed by task shape rather
than by call site:

| Call class | Examples | Model | Why |
| :--- | :--- | :--- | :--- |
| Reasoning (long, generative) | AI interpretation of an incident | Main model, thinking on | Needs deep reasoning; latency is acceptable as it is the final synthesis step |
| Cheap (structured, candidate-selection) | Intent recognition, target resolution, investigation planning | Fast model (vendor default), thinking off | Output is constrained to a candidate list and rule-fallbackable; speed and cost dominate |

The split lands on the `ChatModelGateway` port: `chatClient()` serves the main model (thinking on)
for reasoning, while `chatClient(int timeoutSeconds)` is the dedicated cheap-call entry that routes
to the fast model when one is configured, and falls back to the main model with thinking disabled
otherwise. The runtime depends only on the port, so the routing is an Admin-side implementation
change with **zero** runtime-code edits.

Model selection is converged to the operator, not the end user: the console takes a **vendor + API
key**; `baseUrl`, the main model, and the fast model are resolved by a backend `ModelVendor` map
(e.g. Zhipu → `glm-4.6` main / `glm-4-flash` fast). A `CUSTOM` vendor keeps local deployments
(Ollama / vLLM) unblocked. One key covers a vendor's whole model family, so the abstraction holds.

Measured trade-off (real Zhipu API, 30-case labelled target-resolution eval,
`TargetInterpreterEval.java`):

| | glm-4.6 thinking (before) | glm-4-flash (chosen) |
| :--- | :--- | :--- |
| Target-resolution latency | 11.3 s | 1.9 s |
| Single cheap-call cost | ~0.033 分 | ~0 (vendor-free tier) |
| Resolution accuracy | 86.7% | 76.7% |
| **Error rate** (wrong object, not abstain) | 10.0% | 13.3% |

Accuracy does drop, but the *nature* of the error is controlled: glm-4-flash's misses fall on
ambiguous/trap cases and degrade to clarification, whereas the faster candidate `glm-4-air`
mis-attributed on explicit cases (e.g. read "trade-service instance down" as the instance
`172.16.0.9:7001`). We therefore chose by **error rate, not hit rate** — aligning with the
project's "clarify rather than guess" stance. The eval is a standalone program (not a unit test)
because it depends on a live model; the 30-case size is a stated limit, not a claim of
statistical significance.

Degradation holds without a model: when no model is configured or the model is unavailable,
diagnosis falls back to pure rule-based (`aiAnalysis` is null) and collection continues.

## 7. Current implementation status

**Implemented:**

- Admin Agent Workbench plus the conversational API: `POST/GET /api/agent/sessions`,
  `POST /api/agent/sessions/{sessionId}/messages`, `GET /api/agent/sessions/{sessionId}/workspace`,
  `GET /api/agent/tasks/{taskId}`, `GET /api/agent/tasks/{taskId}/events` (SSE). The legacy `/api/agent/diagnoses*`
  entries were removed together with `DiagnosisController`; only the conversational entries remain.
- Multi-turn follow-ups: AgentContextManager assembles "N most recent messages
  (`rover.agent.context.recent-message-limit`, default 8) + active incident + structured target + key evidence";
  TargetResolver resolves the object as "explicit input → existing routes and instances → model assistance →
  clarification" and never guesses — it creates no task when it cannot resolve one.
- A single orchestration entry point, `AgentOrchestrator`: controllers no longer orchestrate route/metrics/chatClient
  calls themselves, only validate input and map results.
- 任务取消：`POST /api/agent/tasks/{taskId}/cancel` 为协作式取消（标记 `CANCELLED` 并中断执行线程，结论不再产出），
  前端任务卡片在 `PENDING` / `RUNNING` 时显示「取消」按钮；已结束的任务返回 `409 TASK_NOT_CANCELLABLE`。
- 事件接入：`POST /api/agent/events/ingest` 把一次告警转成「路由 + 窗口 + 怀疑点」，独立开会话并记 `ALERT` 来源事件，
  复用与人工提问完全相同的取数链路自动调查；会话按事件隔离，不与人工会话争「单活跃任务」锁。这样监控系统的告警
  （而非只有人工提问）也能驱动一次根因排查，闭合「监控发现问题 → Agent 调查原因」的回路。
- 指标对外出口：默认进程内 `SimpleMeterRegistry`；开启 `management.metrics.export.prometheus.enabled=true` 后
  注册表切换为 `PrometheusMeterRegistry`，由 `/actuator/prometheus` 供抓取，指标名与标签口径不变（`rover.agent.*`）。
- Storage is abstracted behind four repository interfaces with thread-safe in-memory implementations only, so
  **everything is lost on restart**; business code does not depend on maps directly.
- User identity always comes from the backend authentication context (`Authentication.getName()`) and a `userId` in the
  request body is never accepted; sessions, tasks, and incidents are filtered by that identity.
- Read-only collection of routes, instances, metrics, and traces, producing a conclusion, confidence, evidence sources,
  collection timestamps, and limitations.
- Split into `rover-agent-core` / `rover-agent-runtime` / `rover-admin` with one-way dependencies; read-only access is
  guaranteed structurally by the ports.
- Intent recognition and dispatch: a message is first classified (`QUERY_STATE` / `INVESTIGATE` / `EXPLAIN` /
  `ACTION_REQUEST` / `KNOWLEDGE_QUERY` / the not-yet-open `CREATE_INSPECTION`), and a resource target is resolved only
  when needed; state queries run a couple of read-only capabilities, capability questions answer from the registry's
  real list, and unsupported requests are answered honestly instead of forced into an investigation. Unrecognised
  messages with no resolvable target get a self-introduction plus the capability list instead of a path clarification.
- Evidence-driven path correction (without changing the "a query stays a query" semantics): when a lightweight path
  genuinely has nothing useful to produce, the fallback wording is not handed straight to the user — another path is
  tried instead. A state query that cannot tell which kind of fact is being asked for ("what is the state of
  order-service right now") or an explanation question with no conclusion in the session yet is escalated to a
  read-only investigation, provided the object named in the question resolves. A dedicated "路径纠正" step records why
  the shape changed. Correction only happens when the question itself names an object: a question missing its object
  ("what is the state right now") still reports the missing subject honestly instead of restarting an investigation on
  last turn's object.
- The intent prompt has three parts: the environment profile and glossary from `AgentGrounding` (the system is a Gateway
  plus a Nameserver, which hops a call passes through, and what users mean by each colloquialism), the read-only
  capability boundary rendered from the same capability registry, and the allowed intents with their decision order
  plus a few examples. The AI interpretation prompt reuses the same profile, so the two never describe the system
  separately. The model-side policy is "if it is about this system at all, pick the closest intent and honestly report
  MEDIUM or LOW"; `UNKNOWN` is reserved for input unrelated to operations, so an in-domain-but-uncertain message is no
  longer thrown away. The rule layer gained colloquial failure vocabulary (扛不住 / 时好时坏 / 无响应 / 502 …) at the
  same time: the deterministic layer stops missing, and the model fills gaps instead of covering for it.
- Capability registry and read-only executor: available capabilities (route / instance / gateway metrics / trace /
  configuration / registry-event queries) are registered as READ_ONLY, planning may only choose among them, and the
  executor is the single data-access point
  between the model and production data; capabilities without a data adapter are marked unselectable, and action requests
  only produce a non-executable action plan (`executable` is always `false`).
- The investigation chain is orchestrated by a Spring AI Alibaba StateGraph: a planner produces the plan (rule-based
  baseline, model candidates only, out-of-scope steps dropped) and the run advances through a
  PLAN → EXECUTE → EVALUATE loop, where the evaluate node decides whether to keep planning, clarify, or conclude; hitting
  a limit (`rover.agent.planning.*`) stops collection and is stated in the conclusion.
- Hypothesis-driven conclusions: candidate causes are confirmed or eliminated one by one, each with its status
  (confirmed / eliminated / unverifiable), explanation, and evidence sources.
- Diagnosis is strictly read-only; it never changes routes or configuration.
- The console Model Config page accepts an OpenAI-compatible service and takes effect on save with no Admin restart;
  the `ROVER_AGENT_MODEL_CHAT` / `ROVER_AGENT_API_KEY` / `ROVER_AGENT_BASE_URL` / `ROVER_AGENT_MODEL` environment
  variables only seed the first startup when no model config file exists yet, after which the saved file wins. The page
  also offers a connection test (without saving) and a verification of the active config (`POST /api/model/verify`
  returns the active `buildId` and applied-at time, proving the active config is the one just saved).
- When no model is configured or the model is unavailable, diagnosis automatically degrades to pure rule-based
  diagnosis (`aiAnalysis` is null); collection and orchestration are unaffected, and the missing evidence is stated.
- Cheap calls that can always fall back — intent recognition, target resolution, investigation planning — are capped by
  `rover.agent.llm.quick-timeout-seconds` (default 10) and retried once on timeout only: a transient network stall
  either heals itself or falls back to the deterministic path quickly. The AI interpretation is a streaming long call
  and is unaffected, keeping the timeout from the model config.
- The model's explanation draws only on read-only snapshots and never calls management APIs directly: the route and
  instance snapshots — the minimum basis for any explanation — are pre-read by the runtime and written into the prompt,
  while metrics and traces are read on demand through read-only tools executed by the application. The result text of
  the "AI interpretation" step distinguishes runtime pre-fetched snapshots from tools the model called itself, so the
  reasoning traces back to specific snapshots. Every read-only tool description states what it returns, when to use it,
  when not to, and what it cannot return — so the model does not look for traffic data in a route snapshot, or read a
  missing trace as "no failure happened".
- The AI interpretation is pushed as it is generated: `ModelExplainer` streams chunks to the task and Admin relays
  them over SSE. The final full text still lands in the task result, and polling covers dropped connections.
  Collection and rule verdicts are a blocking Graph chain and are not streamed.
- An investigation's conclusion is recorded as an Agent reply, and it is written before the task reaches a terminal
  state: a client that reloads the session on `TASK_COMPLETED` sees the answer itself instead of the
  "investigation started / continued" notice. The reply prefers the model's interpretation and falls back to the
  rule-based conclusion; a failed conclusion callback only logs a warning and never marks a finished investigation
  as failed.
- Task state lives in Admin memory and is gone after restart. Every question opens a Session, but only a fault
  investigation creates a `USER`-origin Incident.
- Intent recognition evaluation loop (`IntentEvaluationTest`): 32 labelled utterances must be matched exactly,
  one by one, by the rule layer; another 6 fuzzy phrasings of the same intents must **not** be settled by the rule
  layer with `HIGH` confidence — the model has to step in. This solves "the intent hit rate is only a verbal claim
  and prompt edits are pure guesswork": the hit rate becomes a regression baseline that can be compared before and
  after a change.
- Strict structured output plus failure classification: `JsonCompletion`'s parsing contract is now supplied by the
  caller (`Function<String, Optional<T>> parser`), and `LlmIntentInterpreter` moved from "lenient default filling"
  to a strict contract — invalid JSON, or any of intent / confidence / topic / action missing or out of range,
  discards the whole result and falls back to the rule layer, with no default value filled in. `ModelCallOutcome`
  splits the outcome into `OK` / `NOT_CONFIGURED` / `UNAVAILABLE` / `TIMEOUT` / `ERROR` / `EMPTY` / `REJECTED`,
  where `rejected` means "the model returned text that violates the contract". This solves "an out-of-range output
  gets patched with a default and the resulting hit-rate loss cannot be located": falling back to the rule layer is
  preferable to swallowing a contract violation.
- Model-call observability including token usage: the `AgentMetrics` port is now
  `modelCall(model, scene, durationMillis, outcome)` plus a new
  `modelTokens(model, scene, promptTokens, completionTokens)`; on the Micrometer side this exposes `model.calls`
  (tags `model` / `scene` / `outcome`), `model.duration` (tags `model` / `scene`), and `model.tokens` (tags
  `model` / `scene` / `kind`, `prompt` or `completion`; usage that is unavailable is not reported at all rather than
  recorded as 0), and removes `model.error`, which could only answer "how many failures". This solves "call counts
  and latency say nothing about cost or context growth": token usage is queryable per scene, so cost and prompt
  bloat can be quantified.
- Prompt-injection isolation (`UntrustedText`): untrusted content is fenced with sentinels (open `<<<ROVER-DATA`,
  close `ROVER-DATA>>>`), `contract()` declares that anything inside the sentinels is not an instruction, and
  `block(label, content)` first replaces any sentinel occurring in the content with the placeholder `[ROVER-DATA]`
  before fencing it. It is applied in `ModelExplainer` (snapshot descriptions), `LlmInvestigationPlanner` (user
  question plus collected evidence), and `ModelTargetInterpreter` (candidate objects plus user question). This
  solves "snapshots / questions / evidence are external content that may coax the model into overstepping": content
  cannot forge the boundary, so the model can separate "data" from "instructions".

**Planned (not implemented):**

- Persistence for sessions / incidents / tasks (Lite ↔ Standard storage modes): Redis for caching and short-term
  context, MySQL for tasks and audit; only in-memory implementations exist today.
- Alert / event ingestion with automatic investigation.
- Graph checkpoint recovery and interrupt-based human approval.
- Log, change-history, and SOP / Runbook retrieval (RAG).
- Write actions, approval flow, audit, and post-execution verification.

## 8. Difference from data-analytics agents

| | Data analytics agent (e.g. Spring AI Alibaba DataAgent) | Rover Ops Agent |
| :--- | :--- | :--- |
| Role | Data analyst | SRE / operations diagnostician |
| Typical question | Why did sales drop? | Why is the service returning 5xx? |
| Core data | Database | Gateway + Metrics + Trace (Logs planned) |
| Core tools | SQL / Python | Route / Instance / Metrics / Trace |
| Output | Data analysis report | Incident diagnosis |
| Action | Data analysis | Remediation (planned) |
| Risk surface | SQL / data | Production infrastructure |
| Goal | Insight | Diagnose + Remediate |

Both use a similar architecture idea — one agent plus graph orchestration with multiple nodes, rather than stacked
multi-agents — but their product goals are entirely different.

## 9. Related documents

- [Admin User Guide](./admin-guide.md): Agent Workbench usage and console model configuration
- [Admin API](./admin-api.md): session / task / event API contract
- [Architecture](./architecture.md): existing Gateway and Nameserver design