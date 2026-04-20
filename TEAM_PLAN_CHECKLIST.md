# GridGarrison Team Plan and Checklist

This plan captures ownership, integration boundaries, and current acceptance criteria for the demo codebase.

## Objective

Deliver a repeatable demo where:

1. EV simulator connects over OCPP with profile-appropriate security.
2. Trust verification gates charging behavior.
3. Escrow lifecycle is visible and deterministic.
4. Digital twin anomaly paths produce clear operator outcomes.

## Team split and ownership

### Person 1: Ingress + Simulator

Scope:

1. OCPP ingress/handshake path.
2. simulator connection lifecycle and dashboard flow.
3. guided user charging APIs and UX state consistency.

Primary files:

1. `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/WebSocketConfig.java`
2. `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/OcppHandshakeInterceptor.java`
3. `src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppWebSocketHandler.java`
4. `ev-simulator/simulator-app/**`

Current status:

- [x] simulator stable connect/reconnect
- [x] guided user flow endpoints and dashboard
- [x] demo-mtls station identity alignment
- [x] startup/reset consistency fixes for user charging

### Person 2: Trust + Blockchain + Escrow

Scope:

1. blockchain verification path and evidence.
2. escrow intent/active lifecycle and settlement transitions.
3. local Ganache bootstrap compatibility.

Primary files:

1. `src/main/resources/solidity/FirmwareRegistry.sol`
2. `src/main/java/com/cybersecuals/gridgarrison/trust/**`
3. trust-related configuration sections in `src/main/resources/application.yml`

Current status:

- [x] contract-backed trust verification route active
- [x] escrow lifecycle integrated into user flow
- [x] retry hardening for common Ganache transient failures
- [ ] production-grade key/cert/secret lifecycle

### Person 3: Watchdog + Visualizer + Panel

Scope:

1. digital twin telemetry and anomaly severity logic.
2. visualizer timeline/snapshot clarity.
3. panel component tamper/hash interactions.

Primary files:

1. `src/main/java/com/cybersecuals/gridgarrison/watchdog/**`
2. `src/main/java/com/cybersecuals/gridgarrison/visualizer/**`
3. `src/main/resources/static/visualizer.html`
4. `src/main/resources/static/panel.html`

Current status:

- [x] anomaly/twin metrics integrated into demo paths
- [x] panel station selection made dynamic for demo-mtls consistency
- [ ] richer anomaly confidence explainability in UI

## Working rules to prevent merge conflicts

1. Keep module ownership boundaries unless handoff is explicit.
2. Merge in small windows when shared contracts change.
3. Prefer adding tests with behavior changes in owned modules.
4. Avoid broad formatting-only diffs across owner boundaries.

## Integration freeze points

1. Shared event names and payload schema between orchestrator/trust/watchdog/visualizer.
2. User-flow API contracts used by `ev-dashboard.html`.
3. Station identity assumptions used across simulator, panel, and handshake security.

## Current acceptance checklist

1. simulator connects and boot/heartbeat works in both `dev-ws` and `demo-mtls`.
2. `POST /api/ev/user/flow/start` succeeds on first attempt when backend env is loaded.
3. post-reset flow status reports `NOT_CREATED` cleanly before first confirm.
4. panel tamper actions affect the same station used by simulator verify flow.
5. anomaly path can surface non-happy terminal state (`REFUNDED`) when triggered.
6. demo can be restarted quickly without stale UI state carrying over.

## Demo rehearsal sequence

1. start backend with local env + `demo-mtls` profile.
2. start simulator with `demo-mtls` profile.
3. run healthy guided charge to `RELEASED`.
4. run tamper/anomaly path and show blocked or refund behavior.
5. show visual evidence across dashboard, panel, and visualizer snapshots.

## Remaining work (team-level)

1. add end-to-end smoke scripts for repeated demo warmup checks.
2. expand cross-module automated tests for trust + escrow + watchdog interplay.
3. harden production security posture (cert lifecycle, secrets, stricter authz).
