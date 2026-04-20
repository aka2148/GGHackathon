# GridGarrison Context

Last updated: 2026-04-19

## 1) Project Purpose

GridGarrison is a demo-first EV charging security platform. It ingests OCPP-style station activity, verifies firmware trust using on-chain/signed baselines, tracks digital twin anomaly signals, and presents a narrative-ready visual state for judges and operators.

The current demo includes a separate EV simulator app with guided user charging and escrow-aware flows.

## 2) Current Runtime Topology

Two applications run together:

1. Backend (root Maven project)
  - HTTPS endpoint on `8443`
  - OCPP WebSocket endpoint `/ocpp/{stationId}`
  - trust, watchdog, visualizer, and panel APIs

2. EV simulator (`ev-simulator/simulator-app`)
  - HTTP dashboard/API on `8082`
  - OCPP client to backend (`ws://` in `dev-ws`, `wss://` in `demo-mtls`)

Typical demo profile pairing is `demo-mtls` on both applications.

## 3) Architecture and Module Boundaries

Backend modules remain event-driven under Spring Modulith:

- `orchestrator`: OCPP ingest + handshake/security checks
- `trust`: blockchain verification + escrow endpoints
- `watchdog`: digital twin metrics, anomaly state, telemetry evaluation
- `visualizer`: runtime trace timeline, snapshot APIs, panel controls
- `shared`: DTOs only

Cross-module behavior is coordinated by events and DTO APIs, not ad-hoc direct service coupling.

## 4) Core Flows Implemented

1. Station connect and OCPP lifecycle
  - Boot, heartbeat, transaction start/update/end, firmware status

2. Trust verification gate
  - simulator requests verify path
  - backend returns gate status and verdict details
  - charging/sim scenarios are blocked until verified

3. User charging + escrow lifecycle
  - intent capture
  - escrow create/fund/authorize polling
  - charging run and settlement
  - terminal outcomes: `RELEASED` or `REFUNDED`

4. Watchdog telemetry integration
  - simulator publishes digital twin telemetry
  - anomaly severity may quarantine/retract and trigger refund-oriented paths

5. Operator pages
  - `visualizer.html`: station/event narrative
  - `panel.html`: component tamper/hash controls
  - `ev-dashboard.html`: simulator user/dev controls

## 5) Recent Stability Fixes Reflected in Code

1. Demo-mtls verification transport correction
  - simulator verify backend URL in `demo-mtls` now uses `https://localhost:8443`

2. Station targeting consistency
  - panel controls no longer hardcode `CS-101`
  - station selection is dynamic/persisted/query-param aware

3. User-flow startup consistency
  - dashboard startup reset retries
  - payment panel state reset on load
  - zero-address escrow values treated as unusable (not valid bindings)

4. Twin control UX hardening
  - polling no longer overwrites unsaved control inputs

5. Diagnostics clarity
  - simulator status reports active runtime profile details
  - trust/contract startup failures map to actionable dashboard guidance

## 6) Current Risks / Known Constraints

1. Local env dependency
  - guided trust/escrow flows require `scripts/local-env.ps1` values for contract + wallet.

2. Port collision risk
  - stale Java processes on `8443` or `8082` can mimic logic failures.

3. Demo certificate coupling
  - in `demo-mtls`, station ID must align with cert CN (`EV-Simulator-001` in current local cert set).

4. Test surface remains narrow
  - simulator controller tests exist, but broad E2E automation is still limited.

## 7) Main Entry Points

Backend:

- `src/main/java/com/cybersecuals/gridgarrison/GridGarrisonApplication.java`
- `src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppWebSocketHandler.java`
- `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/OcppHandshakeInterceptor.java`
- `src/main/java/com/cybersecuals/gridgarrison/trust/service/BlockchainServiceImpl.java`
- `src/main/java/com/cybersecuals/gridgarrison/visualizer/RuntimeTraceController.java`

Simulator:

- `ev-simulator/simulator-app/src/main/java/com/cybersecuals/gridgarrison/simulator/EvWebSocketClient.java`
- `ev-simulator/simulator-app/src/main/java/com/cybersecuals/gridgarrison/simulator/EvSimulatorController.java`
- `ev-simulator/simulator-app/src/main/resources/static/ev-dashboard.html`

## 8) Recommended Next Priorities

1. Expand E2E smoke automation for first-launch user flow and anomaly/refund paths.
2. Improve production-grade cert/key lifecycle and secret handling.
3. Increase watchdog rule transparency with explicit confidence/threshold telemetry in APIs.
4. Add integration tests around trust + escrow behavior under temporary RPC failures.

## 9) Current Demo Assumptions

1. Deterministic behavior is preferred over realism.
2. Local Ganache-backed chain is acceptable for demo trust evidence.
3. Visualizer and simulator pages must remain easy to operate without deep setup overhead.
