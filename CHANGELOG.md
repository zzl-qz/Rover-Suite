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
- Fixed Admin configuration type detection for `gateway.loadbalance.strategy`.

## Release process

Each release must have a Git tag matching the version, a dependency tree, a
checksum file, migration notes, and a tested upgrade path.
