# GridGarrison Context

Last updated: 2026-04-14

## 1) Project Purpose

GridGarrison is a Spring Boot backend for EV charging station trust monitoring. It ingests OCPP-style station traffic over WebSocket, publishes internal events, verifies firmware integrity against on-chain "golden hashes," tracks digital twin health/anomalies, and exposes runtime trace APIs plus a simple UI dashboard.

Core workflow:
- Station connects to `/ocpp/{stationId}`.
- OCPP frames are parsed in the orchestrator and mapped to internal events.
- Trust listeners verify firmware hashes and optionally interact with blockchain.
- Watchdog updates in-memory twins and anomaly counters.
- Visualizer module taps events and serves trace/snapshot endpoints.

## 2) Tech Stack

- Language/runtime: Java 17, Spring Boot 3.3.x.
- Architecture: Spring Modulith (event-driven module boundaries).
- Core Spring starters: Web, WebSocket, Security, Actuator, Data JPA.
- Blockchain: Web3j 4.10.x + Solidity contract (`FirmwareRegistry.sol`).
- Build/tooling: Maven, `web3j-maven-plugin`, Lombok.
- Testing: JUnit 5, Spring Boot Test, Spring Modulith Test, Mockito.
- Databases: H2 (default/dev), PostgreSQL dependency available.
- Frontend (minimal): static `visualizer.html` with polling JS.

## 3) Repository Structure

Top-level:
- `src/main/java/com/cybersecuals/gridgarrison/`
  - `GridGarrisonApplication.java`: entry point + Modulith root.
  - `orchestrator/`: OCPP WebSocket ingestion, message parsing, security/config.
  - `trust/`: blockchain contract wrapper, trust services, listeners/bootstrap.
  - `watchdog/`: digital twin state and anomaly evaluation.
  - `visualizer/`: runtime trace event tap/service/controller.
  - `shared/`: cross-module DTO/support packages.
- `src/main/resources/`
  - `application.yml`: runtime config, SSL, actuator, blockchain properties.
  - `solidity/FirmwareRegistry.sol`: smart contract source.
  - `static/visualizer.html`: lightweight runtime dashboard.
- `src/test/java/...`
  - Modulith boundary test and trust listener tests.
- `target/`
  - Build outputs, generated classes, reports, and generated docs/artifacts.

## 4) Already Implemented

- OCPP WebSocket endpoint `/ocpp/{stationId}` with handshake/subprotocol checks.
- OCPP message parsing and event publication for:
  - Boot notifications
  - Heartbeats
  - Transaction events
  - Firmware status notifications
  - Security event notifications
- mTLS/x509-based security path for OCPP routes.
- Trust services:
  - Retrieve/register golden hashes
  - Verify incoming firmware hash against chain value
  - Session-related on-chain recording path
  - Optional bootstrap/deploy flow for Ganache-style local chain
- Solidity contract and generated Java wrapper (`FirmwareRegistryContract`).
- Watchdog digital twin in-memory lifecycle and anomaly sweep.
- Visualizer event capture with APIs for events/snapshots/simulations.
- Basic tests:
  - Modulith structure verification
  - Trust listener behavior branches (verified/tampered/failure)

## 5) Missing, Incomplete, or Risk Areas

- Security hardening gaps:
  - WebSocket allowed origins configured broadly (`*`).
  - CN-to-`stationId` binding TODO appears not fully enforced.
  - Visualizer endpoints are broadly accessible.
- Watchdog TODOs:
  - Some anomaly rules declared but not fully implemented.
- Trust design inconsistency risk:
  - Session-event recording path may not align cleanly with Solidity contract intent.
- Potentially overlapping trust services:
  - Multiple service paths for firmware verification suggest transitional design.
- Test coverage gaps:
  - Limited tests for WebSocket handshake/security, watchdog logic, visualizer endpoints, and full E2E flow.
- Persistence gap:
  - Data JPA dependency exists, but little/no entity-repository persistence usage identified.
- Build/documentation mismatch risk:
  - Docs mention Maven wrapper commands; wrapper files may be absent.

## 6) Main Entry Points and Interfaces

- Main app: `src/main/java/com/cybersecuals/gridgarrison/GridGarrisonApplication.java`
- WebSocket ingestion: `src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppWebSocketHandler.java`
- Message codec/helpers: `src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppMessage.java`
- Security config: `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/MtlsSecurityConfig.java`
- Trust listener: `src/main/java/com/cybersecuals/gridgarrison/trust/service/FirmwareStatusVerificationListener.java`
- Blockchain service: `src/main/java/com/cybersecuals/gridgarrison/trust/service/BlockchainServiceImpl.java`
- Visualizer API: `src/main/java/com/cybersecuals/gridgarrison/visualizer/RuntimeTraceController.java`

## 7) Working Assumptions For Next Changes

- Keep module boundaries explicit (orchestrator -> events -> trust/watchdog/visualizer).
- Prefer incremental hardening before broad refactors.
- Preserve existing event contracts where possible to avoid breaking visualizer flows.
- Add focused tests for any behavior change in trust/security/watchdog paths.

## 8) Suggested Next Priorities (if aligned with your goals)

1. Security hardening for OCPP + visualizer access controls.
2. Resolve trust/session contract alignment and consolidate duplicate verification paths.
3. Implement remaining anomaly rules and watchdog confidence scoring.
4. Expand automated tests (WebSocket, trust integration, watchdog unit tests, API slices).

## 9) Prioritized Backlog (Demo Grade)

### Now

1. Security hardening with demo-safe defaults:
   - Restrict OCPP WebSocket origins via config (no wildcard by default).
   - Add optional CN-to-stationId enforcement (`off` by default for demo flexibility).
   - Make visualizer exposure config-driven (`public` by default for demo UX).
2. Blockchain correctness:
   - Record session transitions using Solidity `recordSessionEvent(...)` instead of overloading golden-hash storage.
3. Watchdog quality quick wins:
   - Add practical demo heuristics for `RAPID_RECONNECT` and `HEARTBEAT_MISSED`.

### Next

1. Add focused tests for:
   - security config behavior (public vs protected visualizer),
   - trust session event transaction path,
   - watchdog anomaly rules (energy spike, reconnect, heartbeat timeout).
2. Clean up trust service overlap and define one verification path.
3. Improve station identity model (cert CN mapping + allowlist).

### Later

1. Production hardening:
   - stricter authz policy,
   - secure secrets management,
   - stronger observability and failure handling.
2. Full E2E test harness with simulated OCPP clients + Ganache.
3. Persistence strategy for twin history and trust audit records.

## 10) Current Runtime Profile Decisions

- Target mode: hackathon demo grade.
- Current priorities: security hardening, trust/blockchain correctness, watchdog detection quality.
- Blockchain target environment: Ganache reliability first.
- Unresolved preference: whether to include/ignore `target/` artifacts in future commits.
