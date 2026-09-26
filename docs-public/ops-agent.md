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

## 3. Target capability chain

```text
Detect → Investigate → Correlate → Diagnose → Recommend → Approve → Execute → Verify
```

| Stage | Owner | Status |
| :--- | :--- | :--- |
| Detect | Monitoring / alerting, and future event ingestion | Planned |
| Investigate → Diagnose | **Core value of the agent** | In progress (read-only chain orchestrated by a graph, with hypothesis verification) |
| Recommend | Agent | In progress |
| Approve / Execute / Verify | Agent, with a governance layer | Planned |

## 4. Capability levels

### Level A: Observe

The agent queries Route, Instance, Metrics, Trace, and Events runtime data. Read-only.

**Status: implemented.** `InvestigationService` (in `rover-agent-runtime`), driven by `AgentOrchestrator`, collects
route, instance, metric, and trace snapshots and returns a conclusion, evidence sources, and collection timestamps.

### Level B: Reason

The agent builds an investigation plan, selects tools dynamically, correlates evidence, eliminates wrong hypotheses,
and produces a root cause with remediation advice.

**Status: in progress.** Hypothesis-driven investigation is live for the read-only chain: the investigation runs as a
StateGraph where nodes collect facts, conditional edges decide the path, and a synthesis node confirms or eliminates
candidate causes one by one (for example "the path matches no route", "the target service has no matching instance",
"all matching instances are unhealthy", "downstream calls failed recently"), each bound to its evidence sources.
Still missing: model-side dynamic tool selection, investigation plans that adapt as evidence arrives, and an action plan
derived from the root cause.

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
             Incident Task
                   ↓
          Investigation orchestration (StateGraph)
                   ↓
   collectRoute ──conditional edge──→ collectInstances (only for a dynamic service on Nameserver discovery)
                   ↓
        collectMetrics → collectTraces → synthesise (confirm / eliminate hypotheses → conclusion)
                   ↓
   Events / Logs / Change / SOP (planned)
                   ↓
             Root Cause
                   ↓
            Action Plan
```

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
START → collectRoute ──conditional edge──→ collectInstances ─┐
                 └────(skipped)────────────────────────────→ collectMetrics → collectTraces → synthesise → END
```

- Nodes only collect facts and write them into shared state; facts travel through state instead of direct calls.
- The conditional edge skips instance collection when the route did not match, points at a static upstream, or the
  discovery mode is not Nameserver: in those cases instance data cannot judge this route, so skipping avoids both
  pointless calls and misleading evidence.
- Evidence and limitations accumulate via `AppendStrategy`; fact snapshots overwrite via `ReplaceStrategy`, so no node
  relies on side effects.
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
| `rover-agent-core` | `com.rover.agent.core.model` | Session / Incident / AgentMessage / Task / Step / Evidence / Hypothesis / InvestigationReport / TaskView / ResourceTarget |
| | `com.rover.agent.core.snapshot` | Neutral read-only snapshots: RouteSnapshot / InstanceSnapshot / GatewayMetricSnapshot / TraceSnapshot / DiscoveryMode |
| | `com.rover.agent.core.port` | Read-only ports: RouteReadPort / InstanceReadPort / MetricReadPort / TraceReadPort; unavailable data raises SnapshotUnavailableException |
| | `com.rover.agent.core.repository` | Storage interfaces: AgentSessionRepository / AgentMessageRepository / IncidentRepository / AgentTaskRepository (in-memory or persistent implementations are swappable) |
| | `com.rover.agent.core.context` | AgentContextManager (N most recent messages + active incident + structured target + key evidence); TargetResolver / ResourceTarget (explicit input → existing routes and instances → model assistance → clarification) |
| | `com.rover.agent.core.investigation` | RouteMatcher / EvidenceNarrator / InvestigationRules (pure functions, unit-testable without the framework) |
| `rover-agent-runtime` | `com.rover.agent.runtime` | AgentOrchestrator (application entry: context → target → incident → task → investigation); InvestigationService (task lifecycle) |
| | `com.rover.agent.runtime.graph` | StateGraph definition, nodes, conditional edge, conclusion synthesis |
| | `com.rover.agent.runtime.task` | Task lifecycle, Session / Incident registry (through the storage interfaces; in-memory and bounded today) |
| | `com.rover.agent.runtime.repository` | Four thread-safe in-memory implementations (lost on restart), replaced when persistence lands |
| | `com.rover.agent.runtime.tool` | SnapshotTools: exposes the snapshots collected in this run to the model |
| | `com.rover.agent.runtime.llm` | ModelExplainer: model interpretation grounded in runtime pre-fetched snapshots and read-only tools |
| `rover-admin` | `com.rover.admin.agent.adapter` | Four read-only adapters: AdminConfigService → ports, never triggering a write |
| | `com.rover.admin.agent` | AgentController (session / message / task / incident contract) + DiagnosisController (legacy entry, forwarded) + composition root |

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

Today every question lands under a Session: creating a Session also creates a `USER`-origin Incident, and the Task hangs
off that Incident; the TaskView carries `sessionId` / `incidentId`. **Follow-up questions work now**: later messages in a
session carry the N most recent messages, the active incident, the current structured target, and that incident's key
evidence; the active incident is reused when the target matches, a new incident opens only when a clearly different
target is resolved, and a clarification is returned rather than a guess when nothing can be resolved. Session and
Incident live in memory only, so **everything is lost on restart**.

Tool governance, approval policy, and event ingestion for Levels B and C will be split into further sub-packages as
they are built, rather than scaffolding empty modules now.

## 7. Current implementation status

**Implemented:**

- Admin Agent Workbench plus the conversational API: `POST/GET /api/agent/sessions`,
  `GET /api/agent/sessions/{sessionId}`, `POST /api/agent/sessions/{sessionId}/messages`,
  `GET /api/agent/tasks/{taskId}`, `GET /api/agent/incidents/{incidentId}`. The legacy `POST /api/agent/diagnoses`
  and `GET /api/agent/diagnoses/{taskId}` (including the SSE interpretation stream) stay compatible and forward
  internally to the same orchestration.
- Multi-turn follow-ups: AgentContextManager assembles "N most recent messages
  (`rover.agent.context.recent-message-limit`, default 8) + active incident + structured target + key evidence";
  TargetResolver resolves the object as "explicit input → existing routes and instances → model assistance →
  clarification" and never guesses — it creates no task when it cannot resolve one.
- A single orchestration entry point, `AgentOrchestrator`: controllers no longer orchestrate route/metrics/chatClient
  calls themselves, only validate input and map results.
- Storage is abstracted behind four repository interfaces with thread-safe in-memory implementations only, so
  **everything is lost on restart**; business code does not depend on maps directly.
- User identity always comes from the backend authentication context (`Authentication.getName()`) and a `userId` in the
  request body is never accepted; sessions, tasks, and incidents are filtered by that identity.
- Read-only collection of routes, instances, metrics, and traces, producing a conclusion, confidence, evidence sources,
  collection timestamps, and limitations.
- Split into `rover-agent-core` / `rover-agent-runtime` / `rover-admin` with one-way dependencies; read-only access is
  guaranteed structurally by the ports.
- The investigation chain is orchestrated by a Spring AI Alibaba StateGraph; a conditional edge can skip collection
  branches that cannot judge the current route.
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
- The model's explanation draws only on read-only snapshots and never calls management APIs directly: the route and
  instance snapshots — the minimum basis for any explanation — are pre-read by the runtime and written into the prompt,
  while metrics and traces are read on demand through read-only tools executed by the application. The result text of
  the "AI interpretation" step distinguishes runtime pre-fetched snapshots from tools the model called itself, so the
  reasoning traces back to specific snapshots.
- The AI interpretation is pushed as it is generated: `ModelExplainer` streams chunks to the task and Admin relays
  them over SSE. The final full text still lands in the task result, and polling covers dropped connections.
  Collection and rule verdicts are a blocking Graph chain and are not streamed.
- Task state lives in Admin memory and is gone after restart. Every question opens a Session and creates a
  `USER`-origin Incident.

**Planned (not implemented):**

- Persistence for sessions / incidents / tasks (Lite ↔ Standard storage modes): Redis for caching and short-term
  context, MySQL for tasks and audit; only in-memory implementations exist today.
- Alert / event ingestion with automatic investigation.
- Model-side dynamic tool selection and investigation plans that adapt as evidence arrives.
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
- [Admin API](./admin-api.md): diagnosis task API contract
- [Architecture](./architecture.md): existing Gateway and Nameserver design