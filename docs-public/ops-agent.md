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
| Detect | Monitoring / alerting sends through `POST /api/agent/events/ingest` | Implemented (passive ingestion); proactive periodic inspection is not implemented |
| Investigate → Diagnose | **Core value of the agent** | In progress (model-driven conversation path plus a read-only graph chain with hypothesis verification) |
| Recommend | Agent | Implemented for one action: gray-release weight adjustment. The agent can only write a suggestion as a pending change; it cannot execute it |
| Approve / Execute / Verify | Agent, with a governance layer | In progress (approve → precondition → optimistic-lock commit → read-back verification → compensating rollback, complete for one action) |

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

The two paths differ in *who chooses*, and agree on the boundary: on the investigation path the planner produces the plan,
the executor runs it, and an evaluate node decides whether to keep planning, ask for clarification, or conclude; on the
conversation path that choice belongs to the model. What does not change is that both may pick only READ_ONLY capabilities
that are actually wired up in the registry, and the three hard limits — planning rounds, capability calls, and plan steps —
are enforced by code rather than the prompt; hitting them stops collection and is stated plainly in the conclusion.
Remediation plans are opened on exactly one write primitive that really exists — gray-release weight adjustment (see §7) —
and the model can only do one thing with it: register a pending change for human approval. Commit, verification, and
compensation are done by a deterministic executor. Everything else (draining an instance, changing configuration,
restarting, adding or deleting routes) still has no write interface, and no "planned but not executed" plan is produced:
shipping a plan for capabilities that are not implemented only blurs whether the project supports them or merely planned
them once.

### Level C: Act

With authorization, the agent performs limited operational actions such as draining an unhealthy instance, adjusting
routes, changing rate limits, or triggering a rollback.

**Status: in progress (the first action is complete end to end — see §7 "Controlled change loop").** The only executable
action today is `ADJUST_ROUTE_TARGET_WEIGHT`: the model only files a pending change, and after human approval
`RouteWeightActionExecutor` performs precondition check → optimistic-lock commit → read-back verification, with
compensating rollback through the same narrow primitive. Policy, RBAC, and risk grading remain planned — this version
lowers risk by opening one action, exposing the write path to the executor only, and requiring human approval, rather
than pretending the governance layer is complete.

## 5. Two entry points

```text
Engineer-initiated (implemented)                     Event / alert triggered (implemented)
        │                                                        │
        ↓                                                        ↓
  Target resolution (best effort)                          Incident Task
  explicit → existing routes/instances → model                   │
  unresolved still answers (it just lacks an object)              ↓
        │                                          Investigation graph DynamicInvestigationGraph
        ↓                                          (PLAN → EXECUTE → EVALUATE, loops on evidence,
  Conversation path ToolLoopService                 stops at the limits)
  the model decides what to query, how many times,                ↓
  and in which order                                     synthesise (confirm / eliminate /
        │                                                unverifiable → conclusion)
        ↓                                                        ↓
  9 read-only tools + 1 proposal tool (see §7) →           Hypothesis-driven root cause
        │                     limitations
        ↓
  answered while querying; the answer is written back
  to the session as one Agent reply
```

Both paths read data through the same `CapabilityExecutor`: neither the model nor an alert can reach management APIs
directly. The difference is *who decides what to query* — the model on the conversation path (9 read-only tools plus one
proposal tool, sharing a call budget), the planner on the investigation path (picks available capabilities from the
registry, with hypothesis verification). The proposal tool neither reads data through a capability nor writes to the
gateway: it only registers a pending change for human approval, so the "reads must go through `CapabilityExecutor`"
boundary is untouched.

The conversation path has no "classify the question first, then dispatch" layer at all: a sentence asking three things
triggers three lines of querying, and a part it cannot answer is reported as "this part was not found" rather than
abandoning the whole reply.

- **Engineer-initiated:** ask in one sentence on the Admin Agent Workbench (e.g. "why does /api/demo/tt fail?"), with an
  optional collapsible "advanced context" for route / service / instance and a time range; follow-ups reuse the active
  incident. **Implemented.**
- **Alert / event initiated: implemented.** `POST /api/agent/events/ingest` turns one alert into three things — which
  route, which window, what is suspected — and needs login (machine-to-machine entry point, ideally a service account).
  It opens its own session, records an `ALERT`-sourced incident, reuses exactly the same read path as a human question,
  and returns `202` with a task handle. Sessions are isolated per event and owned by no human user, so they never compete
  with human sessions for the "one active task" lock. What is still missing is the **proactive** half: periodic
  self-inspection that raises its own `INSPECTION` incidents.

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
| `rover-agent-core` | `com.rover.agent.core.model` | Session / Incident / AgentMessage / Task / Step / Evidence / Hypothesis / InvestigationReport / TaskView / ResourceTarget / TaskType / AgentStepType |
| | `com.rover.agent.core.snapshot` | Neutral read-only snapshots: RouteSnapshot / RouteUpstreamSnapshot / InstanceSnapshot / GatewayMetricSnapshot / TraceSnapshot / TraceRow / ConfigEntrySnapshot / RegistryEventSnapshot / DiscoveryMode |
| | `com.rover.agent.core.port` | Read-only ports: RouteReadPort / InstanceReadPort / MetricReadPort / TraceReadPort / ConfigReadPort / EventReadPort / LogQueryPort / KnowledgeReadPort (the latter two with LogRequest / LogEntry / KnowledgeEntry); unavailable data raises SnapshotUnavailableException. The only write port is `RouteControlPort` (read state / preview / adjust weight / query operation), used by the deterministic executor alone |
| | `com.rover.agent.core.repository` | Storage interfaces: AgentSessionRepository / AgentMessageRepository / IncidentRepository / AgentTaskRepository / AgentCheckpointRepository / AgentActionRepository (in-memory or persistent implementations are swappable) |
| | `com.rover.agent.core.context` | AgentContextManager (N most recent messages + active incident + structured target + key evidence); TargetResolver / ResourceTarget (explicit input → existing routes and instances → model assistance → clarification) |
| | `com.rover.agent.core.investigation` | RouteMatcher / EvidenceNarrator / InvestigationRules (pure functions, unit-testable without the framework) |
| | `com.rover.agent.core.capability` | CapabilityDescriptor / CapabilityRegistry / CapabilityExecutor / CapabilityResult / AgentGrounding (environment profile and glossary) / UntrustedText (sentinel fencing for untrusted content): capability catalogue and the single read-only execution point |
| | `com.rover.agent.core.planning` | InvestigationPlanner / InvestigationPlan / PlannedStep / PlanValidator / PlanningLimits / RuleBasedPlanner / InvestigationCueDetector (only decides whether the question mentions configuration or events, so the rule-based planner knows whether to append those optional steps): plan production, out-of-scope dropping, hard limits |
| `rover-agent-runtime` | `com.rover.agent.runtime` | AgentOrchestrator (application entry: human message → context → best-effort target → conversation path; machine event → investigation service); InvestigationService (lifecycle of alert-triggered investigations); ToolLoopService (model-driven conversation path) |
| | `com.rover.agent.runtime.graph` | DynamicInvestigationGraph: plan / execute / evaluate / clarify / synthesise nodes, looping conditional edges, conclusion synthesis |
| | `com.rover.agent.runtime.planning` | LlmInvestigationPlanner: rule-based baseline plus model candidates, out-of-scope steps dropped by PlanValidator |
| | `com.rover.agent.runtime.task` | Task lifecycle, Session / Incident registry (through the storage interfaces only; capacity limits mean something for the in-memory implementations) |
| | `com.rover.agent.runtime.repository` | `JdbcAgentStore`: the relational implementation of session / message / incident / task / step / evidence / safe resume points / controlled change records (eight `agent_*` tables, versioned migrations, one transaction per snapshot, foreign keys so no orphans, and startup recovery that turns half-finished changes into failed or uncertain), assembled by `AgentStore`; plus thread-safe in-memory implementations used when no record store path is configured |
| | `com.rover.agent.runtime.tool` | SnapshotTools: exposes the snapshots collected in this run to the model; OpsTools: 9 read-only tools plus 1 proposal tool (data entry point and the single suggestion entry point of the conversation path) |
| | `com.rover.agent.runtime.action` | `AgentActionService` (propose / approve / reject / rollback / resolve, state transitions claimed by CAS) plus `RouteWeightActionExecutor` (the only code that writes to the gateway) and `ActionDraft` / `RouteLocator` |
| | `com.rover.agent.runtime.knowledge` | InMemoryKnowledgeStore + `seedFaq()`: the built-in operations knowledge base, default `KnowledgeReadPort` implementation |
| | `com.rover.agent.runtime.llm` | JsonCompletion / ModelExplainer / ModelTargetInterpreter / SpringAiJsonCompletion / QuickModelCall, plus the conversation-side ChatModelGateway / ConversationModel / SpringAiConversationModel / NoopChatModelGateway (model adapter and honest degradation when no model exists) |
| `rover-admin` | `com.rover.admin.agent.adapter` | Six read-only adapters: Route / Instance / Metric / Trace / Config / Event → ports, never triggering a write; plus `AdminRouteControlAdapter` (the management-API implementation of RouteControlPort, serving the controlled change loop only) |
| | `com.rover.admin.log` | `RecordStoreLogQueryAdapter` (the `LogQueryPort` implementation, mapping string types back to `RecordType`) and TelemetryCollector (periodic sampling of Gateway / Nameserver into the record store, see 6.2) |
| | `com.rover.admin.agent` | AgentController (session / message / task / incident contract) + AgentActionController (approve / reject / rollback / resolve a change) + composition root |

**Boundary rules:**

- `rover-agent-core` depends on neither Spring, Jackson, nor Spring AI: the JSON shape of the Admin management API is
  parsed only in the `rover-admin` adapters, while core rules speak neutral snapshots. That keeps core directly
  unit-testable and data-source agnostic.
- Read-only is structural: the read-only ports expose read methods only, so the runtime cannot reach
  `saveRoute / deleteRoute / updateConfig`. The single write port, `RouteControlPort`, has exactly four methods —
  read state / preview / adjust weight / query operation — with no route creation, deletion, or config editing:
  the capability boundary lives in the interface, not in the prompt or in code review.
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

Every message lands under a Session, and the conversation path binds its Task to an Incident only when the question
points at an object it can resolve (same target reuses the incident, a different one opens a new incident, chit-chat
opens none — the TaskView then carries `sessionId` with an empty `incidentId`). Nothing is blocked on that binding:
an unresolved target still reaches the model, it just has no incident to hang off. **Follow-up questions work now**:
later messages in a session carry the N most recent messages, the active incident, the current structured target, and
that incident's key evidence. Session, Incident, and the tasks, steps, and evidence hanging off them are persisted, so
**the whole chain survives a restart**.

Approval policy and event ingestion for Level C will be split into further sub-packages as
they are built, rather than scaffolding empty modules now.

## 6.2 Model execution strategy

**Status: implemented.** The agent uses two classes of model calls, routed by task shape rather
than by call site:

| Call class | Examples | Model | Why |
| :--- | :--- | :--- | :--- |
| Reasoning (long, generative) | AI interpretation of an incident | Main model, thinking on | Needs deep reasoning; latency is acceptable as it is the final synthesis step |
| Cheap (structured, candidate-selection) | Target resolution, investigation planning | Fast model (vendor default), thinking off | Output is constrained to a candidate list and rule-fallbackable; speed and cost dominate |

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
`TargetInterpreterEval.java`; the same set was run twice on 2026-09-28, and
**the published figures are the two runs pooled, n=60**):

| | glm-4.6 thinking | glm-4-flash (current fast model) |
| :--- | :--- | :--- |
| Average latency per case | 12.2 s (12.0 / 12.5) | 1.2 s (1.1 / 1.2) |
| Single cheap-call cost | estimated from the vendor price list (tokens not measured this run) | ~0 (vendor-free tier) |
| Resolution accuracy (pooled n=60) | **91.7%** (90.0 / 93.3) | **65.0%** (63.3 / 66.7) |
| **Error rate** (wrong object, not abstain) | **8.3%** | **25.0%** |
| Conservative abstain rate | 0.0% | 10.0% |

Method: pooled rather than single-run because one pass over 30 cases moved by ±3.3pp between the
two runs, while both pointed the same way. On trap and ambiguous cases the expected answer *is*
"no object", so an abstention counts as a hit — the accuracy figure therefore includes "correctly
refused to guess". In each run, 2 of glm-4.6's cases (C6, D5) scored correct only because they hit
the 30-second timeout and degraded to clarification — 4/60 overall; excluding them leaves
**85.0% decided-by-the-model accuracy** (51/60).

**Errors that repeated in both runs** (systematic, not variance): B5 and B6 — a trap case that
should abstain but answers `/api/order`, and an ambiguous case that answers `order-service`; the
fast model additionally repeats A6 / B5 / C6 / D5 / E2 / E4 — abstain cases answered anyway, a
service name read as an instance address, a path read longer than given.

The gap to the first eval (86.7% / 76.7%) sits inside the same variance band. One conclusion from
that run did not hold: back then glm-4-flash's misses were mostly conservative clarifications,
while these runs produced an outright mis-attribution (read "trade-service instance down" as the
instance `172.16.0.9:7001`) — the same failure class that ruled out `glm-4-air`. So **"the fast
model's error nature is controlled" currently lacks stable evidence**. The selection criterion
stays error rate rather than hit rate, and the fast model still only handles cheap calls that have
a rule fallback: a wrong target resolution degrades to clarification instead of driving an
investigation with the wrong object.

The eval is a standalone program, not a resident unit test (it needs the online model); 60 cases
is its known limit. Tool selection is measured separately, below.

### 6.2.1 Tool-selection eval (20 cases)

Target resolution asks "did it pick the right object"; tool selection asks "did it call the right
tool". **The latter has no fallback**: a wrong target resolution degrades to clarification — the
worst outcome is not answering — while a wrong tool returns irrelevant evidence that the model then
reasons over, producing a well-argued wrong conclusion. So this layer is measured on its own
instead of resting on "the end-to-end run looked fine".

The production path is what runs: prompts, tool set and loop are the real `ToolLoopService` and
`OpsTools`; only the eight read-only ports are stubbed (fixed data, so the model always finds
something) for observation. Judgement does not trust the model's own account — data access always
goes through `CapabilityExecutor`, so **executed capabilities plus step details** are the credible
evidence that a tool was really called. The expected tool set is derived mechanically from "which
data could possibly answer this question", **not by another model**, so there is no circular
argument.

20 cases: state 4 / route 2 / trace 1 / config 1 / event 1 / history 2 / knowledge 2 / open-ended
investigation 2 / negative 4 / multi-question 1. Result (glm-4.6, 2026-09-28, 20/20 usable):

| Metric | Result |
| :--- | ---: |
| Selection accuracy | **90.0%** (18/20) |
| Missed (needed tool not called) | **0.0%** |
| Extra (irrelevant tool called) | 10.0% (2/20) |
| Wrong arguments | **0.0%** (9 checks, all pass) |
| Negative cases with zero data access | **4/4** |
| Average latency | 18.5 s (5.3–53 s) |

Both misses are **extra calls, not wrong conclusions**: "which hop failed" also pulled gateway
metrics, "any instances coming or going?" also read logs — capabilities adjacent to the right
answer, paid for in latency (about 13 s → 41 s / 20 s) rather than in correctness. To cut latency,
those two are the place to tighten the prompt; correctness is not the worry here.

The 4/4 negative result is the valuable part: chit-chat, "what can you do?", and a write request
like "take /api/order offline" all ended with **zero tool calls** — the read-only boundary holds on
the model side too, not only as a port-structure constraint.

Limits: one run of 20 cases, no second round and no fast-model comparison yet; the open-ended
investigation cases allow extra reads, so those judge only "did it hit the point" and their extra
rate is understated.

Degradation holds without a model: on the investigation graph, diagnosis falls back to pure rule-based (`aiAnalysis` is
null) and collection continues. The conversation path deliberately does **not** fake a graceful degradation: its every
query and every sentence of its answer come from the model, so it replies "no usable model is configured, please fill one
in on the model configuration page" and closes the task with `LOW`.

## 6.3 Record store and telemetry collection

"What happened earlier" cannot be answered from live monitoring alone, so Admin keeps a local record store
(`RecordStore`). Data is written continuously while Admin runs, whether or not anyone asks a question — even without a
model configured.

```text
Writers                                     Storage                                Readers
Admin config writes (change/rollback/err)─┐              ┌→ LogQueryPort ──→ CapabilityExecutor ──→ tool queryLogs
                                          ├→ RecordStore (local H2, dual-queue async write)
Telemetry collector (polls manage APIs)───┘              └→ (retention cleaned per category)
Built-in FAQ (InMemoryKnowledgeStore)─────────────────────── KnowledgeReadPort ──→ tool searchKnowledge
```

**Write path (`RecordStore` / `JdbcRecordStore` in `rover-common`):**

- Dual-queue async write: diagnostic evidence (critical) is kept as much as possible and waits briefly when the queue is
  full; high-volume telemetry is best-effort and dropped when full, so no business request ever waits on storage.
- Location comes from `rover.admin.log-store-path` (default `./rover-logs/rover`, real file `rover-logs/rover.mv.db`); the
  JDBC URL carries `AUTO_SERVER=TRUE`, so another process can open the same URL read-only while the app is running.
- Schema changes run through versioned migrations (`schema_migrations`, forward-only): an existing old database gets
  incremental `ALTER`s instead of being skipped wholesale like `CREATE TABLE IF NOT EXISTS`.
- The data lives only on local disk: wipe the directory or move hosts and it is gone. It is not a cross-host audit source.

**What gets written (`RecordType`):**

| Record type | Meaning | Producer | Category and retention |
| :--- | :--- | :--- | :--- |
| `CONFIG_CHANGE` | Admin config write succeeded: route save / delete, dynamic config change | AdminConfigService | Diagnostic evidence (`log-retention-days`, 30 days) |
| `ROLLBACK` | Rollback succeeded (a rollback is itself a change) | AdminConfigService | same as above |
| `ERROR` | Config write failed (including delivery to Gateway) | AdminConfigService | same as above |
| `INSTANCE_EVENT` | Component reachability flip, instance register / deregister / health flip | TelemetryCollector | same as above |
| `METRICS_SAMPLE` | One aggregated metric snapshot per component per cycle, for time series rather than a single point | TelemetryCollector | Telemetry (`log-telemetry-retention-days`, 3 days) |
| `REQUEST_TRACE` | Slow requests and 5xx traces, deduplicated by traceId | TelemetryCollector | same as above |

Reserved with no producer yet: `DEPLOY` / `AGENT_ACTION` / `MEMORY` / `HEARTBEAT` / `GENERIC`. Raw heartbeats and
per-request logs are deliberately not stored: too noisy for a model, and the normal state is already covered by the
metric-sample time series.

**Telemetry (`TelemetryCollector`):** interval `rover.admin.log-collect-interval-seconds` (default 30s, minimum 5s; the
first run is delayed by one cycle so beans are ready). Each round pulls STATUS / METRICS / INSTANCES / TRACES from the
Gateway and Nameserver manage APIs: components are checked for a reachability flip; instances keep a health baseline (the
**first round only seeds it and writes no events**, so startup does not flood the store with "everything registered"
events), after which only register / remove / up / down flips are recorded; metrics are written as one aggregate snapshot
per component per cycle; traces keep only slow requests and 5xx under the `error` filter, deduplicated by traceId in a
bounded set (10 000). A retention task runs every 60 minutes and purges each category with its own retention window.

**The two read-only capabilities exposed to the model:** historical evidence via `LogQueryPort` (`queryLogs` filters by
type / target / time range) and operational knowledge via `KnowledgeReadPort` (`searchKnowledge`, backed by the built-in
FAQ in `InMemoryKnowledgeStore`, not by the record store). Both are registered as available READ_ONLY capabilities in the
catalogue described in §7.

## 7. Current implementation status

**Implemented:**

- Admin Agent Workbench plus the conversational API: `POST/GET /api/agent/sessions`,
  `POST /api/agent/sessions/{sessionId}/messages`, `GET /api/agent/sessions/{sessionId}/workspace`,
  `GET /api/agent/tasks/{taskId}`, `GET /api/agent/tasks/{taskId}/events` (SSE), `POST /api/agent/tasks/{taskId}/cancel`,
  `POST /api/agent/events/ingest`. The legacy `/api/agent/diagnoses*` entries were removed together with
  `DiagnosisController`; only the conversational entries remain.
- Conversation path `ToolLoopService` (model-driven evidence collection): once a question arrives, **the model itself
  decides what to query, how many times, and in what order** — rather than first classifying the question into "state
  query / investigation / capability summary" and running a different pipeline per shape. Every tool call is a real read,
  and all read-only tools run through the same `CapabilityExecutor`: the model never sees management-API credentials.
  There are 9 read-only tools, one per capability in the registry:

  | Tool | What it reads | Capability |
  | :--- | :--- | :--- |
  | `listRoutes` / `getRoute(path)` | Routes with prefixes, target service and group, static targets, prefix stripping | ROUTE_QUERY |
  | `listInstances(serviceName)` | Registered instances, address and port, health | INSTANCE_QUERY |
  | `getGatewayMetrics(path)` | Global window plus per route × upstream instance: requests, status codes, error rate, latency | GATEWAY_METRICS_QUERY |
  | `getTraces(path)` | Sampled traces (phases, durations, status codes, traceId) | TRACE_QUERY |
  | `getConfigs()` | Effective Gateway / Nameserver configuration | CONFIG_READ |
  | `listRegistryEvents()` | Recent register / deregister / evict / health-mark events | EVENT_QUERY |
  | `queryLogs(type, target, hours)` | Recorded evidence: CONFIG_CHANGE / ROLLBACK / ERROR / INSTANCE_EVENT / METRICS_SAMPLE / REQUEST_TRACE | LOG_QUERY |
  | `searchKnowledge(query)` | Built-in operations FAQ: how to configure, onboard, and troubleshoot | KNOWLEDGE_RETRIEVAL |

  Each tool description states what it returns, when to use it, when not to, and what it cannot provide, so the model does
  not ask a route snapshot for traffic or read "no traces" as "no problem". The call budget is enforced on both sides:
  `OpsTools.MAX_CALLS` (30) and `spring.ai.tools.limits.max-total-tool-calls` (30) are kept in sync, with a per-tool
  default limit of 10.

  The tenth tool is the only non-read-only entry point, and its name is the boundary:
  `proposeTargetWeightChange(route, group, desiredWeight)` — *propose*, not *execute*. It reads the routes once, checks the
  version target and its current weight, then registers a `PENDING_APPROVAL` change and answers "not executed yet";
  **no path from it reaches a write call** (`OpsToolsProposalTest` pins this down with a fake gateway that fails on any
  write method). It deliberately does not advance the safe resume point either: resume points assume "redoing has no side
  effect", while this call writes a row and redoing it would leave a second pending card behind.
- **Controlled change loop (`ADJUST_ROUTE_TARGET_WEIGHT`, the first and currently only action)**: model proposal →
  pending approval → human approval → deterministic execution → read-back verification → compensating rollback, each step
  with its own state and code:
  - **Observe / Plan**: `proposeTargetWeightChange` reads the routes once (revision plus per-version weights) and registers
    a `PENDING_APPROVAL` change. **The gateway is not touched by a single byte.** No matching route, missing version, or an
    unchanged weight all return a reasoned "why this cannot be proposed" instead of forcing a card into existence.
  - **Approve**: `POST /api/agent/actions/{actionId}/approve|reject|rollback|resolve`. "Pending → executing" is an atomic
    state transition (`AgentActionRepository.transition`), so triple-clicking approves and executes exactly once; a change
    belongs to the user of the session that created it, and somebody else's change is treated as nonexistent (404).
  - **Precondition**: re-read the whole table and compare revision, target existence, and current weight against the
    proposal. An approval card may have been on screen for ten minutes; routes someone else changed in that window must not
    be overwritten by it — on mismatch the action lands in `PRECONDITION_FAILED` and **no write request is sent**.
  - **Execute**: preview (the candidate table is validated and diffed by the gateway, without persisting or applying) →
    **persist the `operationId` before sending the request** → commit with the revision read at proposal time.
  - **Verify**: a 200 from the gateway only means the request was accepted; whether the goal was reached has to be read
    back. A mismatch is `FAILED` — claiming success without reading back is lying.
  - **Rollback**: a compensating action, not a whole-table rollback. It puts only the target this change touched back to
    `beforeWeight`, committed with the *current* revision; reverting the whole table to an old version would wipe out
    whatever others changed afterwards.
- **`revision` and `operationId` solve two different problems**: the former is optimistic locking (never overwrite someone
  else's update with stale state; a conflict returns 409 and the change lands in `PRECONDITION_FAILED`), the latter is
  idempotency (never let one operation execute twice because of a retry). So after a timeout the only correct move is
  **re-querying with the original operationId**: `APPLIED` means continue verifying, confirmed-not-applied means retrying
  is safe, and an unreachable gateway means `UNCERTAIN` — never resubmit under a fresh id, which is exactly how one change
  gets executed twice.
- **No model inside the executor**: `RouteWeightActionExecutor` is plain deterministic code (precondition, CAS, call,
  re-query, verify), and every state it records is backed by facts the program verified. `UNCERTAIN` therefore has to
  exist: "the write was sent, no response arrived, and the re-query is unreachable" is neither success nor failure.
- **Restart recovery**: a half-finished change is classified by whether its idempotency key reached the database — no key
  means the write never left (`FAILED`), a key means it may have applied (`UNCERTAIN`, confirm with the same id); an
  interrupted rollback returns to `SUCCESS` so the operator can click rollback again.
- **Change record (`agent_action`)**: one row holds everything a change is based on and produced — target, both weights,
  expected revision, both idempotency keys, applied revision, requester / approver, preview diff, failure note, and a row
  version (schema v4). Deleting the session removes it; retiring a task only nulls the foreign key, because the audit value
  of a change outlives the link that created it.
- **Workbench "Changes" card**: lists changes and only offers the buttons the server-side state allows (pending →
  approve / reject; succeeded → rollback; uncertain → re-confirm). Execution is synchronous, so the response is terminal;
  the UI never turns a change green optimistically — only the server's read-back decides whether it took effect.
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
- Storage is abstracted behind four repository interfaces and business code never touches maps directly: with a record
  store path configured it uses `JdbcAgentStore` (relational tables, the whole chain recoverable after a restart);
  with none it falls back to the thread-safe in-memory implementations (lost on restart — the path taken by unit tests
  and stateless runs).
- User identity always comes from the backend authentication context (`Authentication.getName()`) and a `userId` in the
  request body is never accepted; sessions, tasks, and incidents are filtered by that identity.
- Read-only collection of routes, instances, metrics, traces, configuration, registry events, historical logs, and
  operational knowledge, producing a conclusion, confidence, evidence sources, collection timestamps, and limitations.
- Recorded evidence and telemetry collection (see §6.3): config changes / rollbacks / failed writes, component and
  instance health flips, per-cycle aggregated metric samples, and slow or 5xx traces are written to the local record
  store whether or not anyone asks a question. Live data still comes from manage APIs; only historical evidence goes
  through `queryLogs`.
- Split into `rover-agent-core` / `rover-agent-runtime` / `rover-admin` with one-way dependencies; read-only access is
  guaranteed structurally by the ports.
- Capability registry and read-only executor: available capabilities (route / instance / gateway metrics / trace /
  configuration / registry-event queries) are registered as READ_ONLY, planning may only choose among them, and the
  executor is the single data-access point
  between the model and production data; capabilities without a data adapter are marked unselectable. No write capability
  exists anywhere in the catalogue: writes go down a separate, much narrower chain — `RouteControlPort` (read state,
  preview, adjust weight, query operation) → `RouteWeightActionExecutor` (triggered only by human approval). A remediation
  request therefore still gets read-only facts and rationale, at most plus one pending change awaiting human approval —
  never a plan that pretends to be executable.
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
- Cheap calls that can always fall back — target resolution, investigation planning — are capped by
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
  as failed. On the conversation path the conclusion *is* the model's full answer, and its confidence is recorded as
  `MEDIUM` — the facts are checkable, but the reasoning still comes from the model.
- Every question lands under a Session and its task is best-effort attached to an Incident (same target reuses the
  incident, a clearly different target opens a new one, chit-chat creates none). Sessions, messages, incidents, tasks,
  steps, and evidence all go to the record store and **remain queryable after a restart**, so a historical session can
  be reopened as-is. Tasks still PENDING / RUNNING at startup are marked `INTERRUPTED` by the store — not failed: the
  collected steps and evidence are kept and `error` names the latest safe resume point. That difference is the whole
  point: interrupted means it can be picked up again, failed does not.
- **A safe resume point (`agent_checkpoint`) is a business-level contract, not a framework state snapshot**: it means
  "up to here everything is settled and it is safe to continue from this point", and there are exactly six of them —
  task started, plan settled, every tool returned, round evaluated, synthesis done, task completed. The per-tool point
  is the important one: **tool calls are where the agent actually produces new facts**, so checkpointing only per
  planning round would re-run facts a crashed round had already collected. A crash in the middle of a tool call does
  not advance the point; recovery repeats that one read-only call (harmless while everything is read-only).
  Write actions therefore never rely on resume points: the proposal tool does not advance one (redoing it would leave a
  second pending card), and both commit and compensation persist their `operationId` before sending the request, so a
  restart can classify a half-finished change as failed or uncertain by whether the key reached the database.
- **A resume point stores the high-water mark, not a list**: `stage` names the business stage (`STARTED` / `PLANNED` /
  `TOOL_COMPLETED` / `ROUND_EVALUATED` / `SYNTHESIS_COMPLETED` / `COMPLETED`) plus round number, completed call count,
  and step/evidence counts. Which steps and evidence those are is read back from the relational tables by sequence —
  no ID lists are copied here. The graph node name is a diagnostic field only (`runtime_node`); recovery never depends
  on it, so swapping the agent framework or renaming nodes cannot change recovery semantics. `resume_state_json` is
  reserved for runtime state that cannot be rebuilt from the tables; both current paths can be rebuilt, so it stays
  empty rather than holding invented content.
- **Facts and the resume point are written together**: task state, steps, evidence, and the checkpoint land in one
  transaction, so "the resume point claims 3 evidence rows while the database holds 2" cannot happen. That is also why
  evidence became **incremental**: it is persisted as soon as a tool returns, instead of being written in one batch
  when the conclusion is composed. The task view's `evidence` is everything this run has collected; `result.evidence`
  is the same batch once a conclusion exists.
- **Schema and its migration rule**: the agent tables (session / message / incident / task / step / evidence + safe
  resume points + controlled change records) are the project's database schema baseline, managed through
  `agent_schema_migrations` (v1 creates the aggregate, v2 drops the legacy JSON snapshot tables, v3 adds resume points,
  v4 adds change records). This is the only release allowed to
  rebuild the schema; from here on, migrations are only ever added, never edited, and nobody is asked to wipe the
  database again. The references are foreign keys rather than conventions: session → incident → task → steps /
  evidence / checkpoints, with changes hanging off both session and task, so deleting a session removes the whole chain
  and orphans cannot exist.
- Strict structured output plus failure classification: `JsonCompletion`'s parsing contract is now supplied by the
  caller (`Function<String, Optional<T>> parser`), and `LlmInvestigationPlanner` / `ModelTargetInterpreter` follow a
  strict contract — invalid JSON, or a value out of range, discards the whole result and falls back to the rule layer,
  with no default value filled in. `ModelCallOutcome`
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

- The next storage step: today it is a single local H2 file (Lite); the same SQL with a different connection source is
  what a MySQL Standard profile needs. Redis caching and cross-host audit are not implemented.
- Resuming from a resume point: alert investigations (`INVESTIGATION`) have well-defined stage state and a settled
  capability set, so they can genuinely resume — read the latest checkpoint, rebuild the task from the persisted
  steps / evidence / executed capabilities, and continue from the next safe stage without repeating finished tool
  calls. Human conversations (`CONVERSATION`) still only go as far as "mark interrupted, keep the facts, allow a
  retry" — this version does not reach into Spring AI's conversation loop looking for a recovery cursor; a retry hands
  the facts already collected back to the model.
- Proactive inspection: periodic self-inspection that raises its own `INSPECTION` incidents. `POST
  /api/agent/events/ingest` already lets an external alert drive an automatic investigation; what is missing is the
  agent going out to look on a schedule.
- Record store off-box: today it is a local H2 per Admin instance, so replicas each write their own history; a shared
  time-series / log backend is needed before it counts as cross-host audit.
- Graph checkpoint recovery and interrupt-based human approval: the controlled change loop already has human approval
  (a REST endpoint plus a state CAS), but it runs "execute after approval" rather than making approval an interrupt inside
  the graph; the graph itself is still not wired to interrupt.
- External SOP / Runbook retrieval: historical logs (`queryLogs`) and the built-in FAQ (`searchKnowledge`) are live, but
  loading a team's own runbooks and change records in bulk — real RAG with chunking, embeddings, and citations — is not.
- Every other write action and the full governance layer: actions beyond gray-release weight adjustment (draining
  instances, editing configuration, deleting routes, restarting), risk grading for actions, automatic approval and
  automatic rollback, cross-host audit. The one action that is implemented uses the most conservative combination
  available: a single action, the write path exposed only to the executor, mandatory human approval, a read-back before
  success, and compensation through the same narrow primitive.

## 8. Difference from data-analytics agents

| | Data analytics agent (e.g. Spring AI Alibaba DataAgent) | Rover Ops Agent |
| :--- | :--- | :--- |
| Role | Data analyst | SRE / operations diagnostician |
| Typical question | Why did sales drop? | Why is the service returning 5xx? |
| Core data | Database | Gateway + Instances + Metrics + Trace + record store + operations knowledge |
| Core tools | SQL / Python | 9 read-only tools: Route / Instance / Metrics / Trace / Config / Events / Logs / Knowledge (plus 1 tool that files a change for approval) |
| Output | Data analysis report | Incident diagnosis (plus a remediation plan awaiting human approval) |
| Action | Data analysis | Controlled remediation: one action today — gray-release weight adjustment, executed and read back after human approval (the rest is planned) |
| Risk surface | SQL / data | Production infrastructure |
| Goal | Insight | Diagnose + Remediate |

Both use a similar architecture idea — Rover Ops Agent is likewise a single agent: the conversation path lets the model
decide what to query within one round, and only the investigations that need orchestration go to the StateGraph
(plan / execute / evaluate loop) — rather than splitting the problem among stacked sub-agents. The product goals,
however, are entirely different.

## 9. Related documents

- [Admin User Guide](./admin-guide.md): Agent Workbench usage and console model configuration
- [Admin API](./admin-api.md): session / task / event API contract
- [Configuration reference](./configuration-reference.md): every Agent and record-store setting
- [Architecture](./architecture.md): existing Gateway and Nameserver design