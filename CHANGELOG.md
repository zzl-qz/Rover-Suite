# Changelog

All notable changes to Rover-Suite are documented here. Releases follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

- Unified the project under Apache License 2.0.
- Added Admin, configuration, deployment, troubleshooting, performance and
  extension documentation.
- Added a Docker Compose local demo and third-party attribution summary.
- Added a complete plugin development/integration guide in English and Chinese,
  a Chinese Admin API reference, a full configuration reference, and a
  bilingual release checklist.
- Added an H2-backed record store with asynchronous write, versioned migration,
  and per-type retention, capturing config-change / rollback / error /
  instance-event / metrics-sample / slow-or-error-trace evidence.
- Added a telemetry collector that periodically samples Gateway/Nameserver
  metrics, component/instance health transitions, and slow/error traces into
  the record store.
- Added Agent historical-log query and built-in operational-knowledge
  retrieval capabilities (`LOG_QUERY` / `KNOWLEDGE_RETRIEVAL`), answering
  "what changed / what failed" and "how to configure / onboard" questions.
- Documented the model-driven conversation path (`ToolLoopService` with nine
  read-only tools), the record store and telemetry collector, the task-cancel
  API, and the alert-ingestion API across the bilingual Ops Agent guide,
  configuration reference, Admin API reference, and user guide.
- Added the first controlled operational action, `ADJUST_ROUTE_TARGET_WEIGHT`:
  the agent can only file a pending change (a tenth, proposal-only tool), and a
  human approval in the Workbench triggers a deterministic executor that
  re-checks the revision and target weight, commits under the gateway's
  optimistic lock with a pre-persisted `operationId`, reads the route back to
  verify, and compensates a failed change through the same narrow primitive.
  Unknown outcomes stay `UNCERTAIN` and are resolved by re-querying that same
  `operationId`, never by resubmitting. Persisted in a new `agent_action` table
  (schema v4).
- Fixed Admin configuration type detection for `gateway.loadbalance.strategy`.

## Release process

Each release must have a Git tag matching the version, a dependency tree, a
checksum file, migration notes, and a tested upgrade path.
