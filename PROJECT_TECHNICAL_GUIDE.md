# GridGarrison Technical Project Guide

This is the current technical handoff for the repository as of April 2026.

## 1. System overview

GridGarrison is a deterministic EV trust and anomaly demo composed of:

1. Backend service (root Maven project)
   - OCPP WebSocket ingress (`/ocpp/{stationId}`)
   - mTLS-aware identity checks
   - trust verification against blockchain/signed baselines
   - escrow lifecycle and settlement APIs
   - digital twin and anomaly services
   - visualizer and panel operator pages

2. EV simulator service (`ev-simulator/simulator-app`)
   - persistent OCPP client (ws/wss)
   - REST control plane for demo actions
   - guided user charging flow and payment/escrow UI
   - digital twin control and telemetry hooks

## 2. Runtime architecture

### 2.1 Backend modules

```text
com.cybersecuals.gridgarrison
├── orchestrator (websocket, handshake, security integration)
├── trust        (blockchain, trust verdicts, escrow routes)
├── watchdog     (digital twin station metrics/anomaly state)
├── visualizer   (runtime snapshots/events and panel APIs)
└── shared       (cross-module DTOs)
```

### 2.2 Event model

Primary path:

1. simulator sends OCPP-style frames
2. orchestrator parses and publishes domain events
3. trust and watchdog consume events
4. visualizer tap records runtime narrative

Module boundaries are enforced by Spring Modulith tests and package design.

## 3. Profiles and network shape

### 3.1 Backend

- port: `8443`
- profiles:
  - `dev-ws`: SSL disabled
  - `demo-mtls`: SSL enabled with optional connector client-auth (`GG_CLIENT_AUTH=want` default)

### 3.2 Simulator

- port: `8082`
- profiles:
  - `dev-ws`: gateway `ws://localhost:8443/ocpp/{stationId}`
  - `demo-mtls`: gateway `wss://localhost:8443/ocpp/{stationId}` with JKS certs

Critical `demo-mtls` behavior:

- simulator verify backend base URL must be `https://localhost:8443`
- station ID must match client certificate CN (current demo cert expects `EV-Simulator-001`)

## 4. Backend module responsibilities

### 4.1 Orchestrator

Key classes:

- `orchestrator/config/WebSocketConfig.java`
- `orchestrator/config/OcppHandshakeInterceptor.java`
- `orchestrator/config/MtlsSecurityConfig.java`
- `orchestrator/websocket/OcppWebSocketHandler.java`

Responsibilities:

- validate OCPP subprotocol (`ocpp2.0.1`)
- enforce station ID route checks and certificate identity checks
- decode OCPP action payloads and publish domain events

### 4.2 Trust

Key classes:

- `trust/service/BlockchainServiceImpl.java`
- `trust/service/BlockchainTrustService.java`
- `trust/contract/FirmwareRegistryContract.java`
- trust controller routes for verify and escrow APIs

Responsibilities:

- retrieve and verify golden hash plus signed evidence
- expose latest verdict APIs
- maintain escrow intent and active lifecycle views
- integrate with on-chain state transitions and compatibility fallbacks

### 4.3 Watchdog

Key classes:

- `watchdog/service/DigitalTwinServiceImpl.java`
- `watchdog/service/StationTwin.java`

Responsibilities:

- maintain station twin metrics and status
- process telemetry severity and anomalies
- support reset and metrics APIs used by simulator dev controls

### 4.4 Visualizer

Key classes:

- `visualizer/RuntimeTraceService.java`
- `visualizer/RuntimeTraceController.java`
- `visualizer/RuntimeEventTap.java`

Responsibilities:

- expose timeline and snapshot APIs
- power `visualizer.html`
- support panel workflows for hash generation and component tamper simulation

## 5. Simulator internals

Key classes:

- `EvWebSocketClient`
- `EvSimulatorController`
- `EvSimulationScenarios`
- `EvTelemetryProfileProperties`
- `EvUserJourneyState`
- `EvDigitalTwinRuntimeState`
- `EvVerificationGateState`

Capabilities:

1. Core OCPP simulation
   - boot, heartbeat, transaction, firmware status

2. Guided user flow
   - `POST /api/ev/user/flow/start`
   - `GET /api/ev/user/flow/status`
   - `POST /api/ev/user/flow/complete`
   - `POST /api/ev/user/flow/reset`

3. Intent and wallet APIs
   - `POST /api/ev/user/intent`
   - `GET /api/ev/user/wallet`
   - `POST /api/ev/user/wallet/topup`

4. Digital twin controls
   - `GET /api/ev/dev/digital-twin/status`
   - `POST /api/ev/dev/digital-twin/controls`

## 6. End-to-end flow (current)

### 6.1 First-launch user journey

1. dashboard loads in user mode
2. client-side reset clears stale intent/payment state
3. backend reset clears escrow/watchdog binding
4. user confirms amount (flow start)
5. verify gate must pass
6. escrow must reach `AUTHORIZED`
7. charging starts and auto-updates
8. complete/settle reaches `RELEASED` or `REFUNDED`

### 6.2 Tamper and anomaly path

1. panel or scenario introduces tampered component/hash
2. trust verdict downgrades and/or watchdog severity escalates
3. user flow can be blocked or retracted
4. settlement may transition to `REFUNDED`

## 7. Operational commands

Backend (demo-mtls with env):

```powershell
. .\scripts\local-env.ps1
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Simulator:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Focused simulator tests:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml -Dtest=EvSimulatorControllerTest test
```

## 8. Known gotchas

1. Port conflicts (`8443`, `8082`) can leave stale process confusion.
2. Running `mvn spring-boot:run` from repo root starts backend, not simulator.
3. Missing `GG_CONTRACT_ADDR` or wallet env breaks trust/escrow verification paths.
4. Station ID mismatch in demo-mtls fails OCPP handshake due CN check.

## 9. Immediate improvement targets

1. Add stable E2E smoke scripts for first-launch user flow assertions.
2. Expand integration tests around trust and escrow lifecycle retries.
3. Harden docs and scripts for cross-platform startup parity.
