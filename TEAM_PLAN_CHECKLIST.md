# GridGarrison Team Plan and Checklist (3-Person, No-Conflict Split)

## Objective
Build a live demo where:
1. A separate dummy EV app connects with its own cert.
2. Charging is simulated over a persistent live connection.
3. Anomalies are detected and responded to in near real time.
4. Trust decisions are anchored to Ganache-backed on-chain data.

---

## Working Rules (to avoid merge conflicts)
1. Each engineer owns separate modules and files.
2. No one edits another owner's files without explicit handoff.
3. Shared contracts are updated only during integration windows.
4. Use one branch per owner:
   - feature/p1-ingress-ev
   - feature/p2-trust-ganache
   - feature/p3-watchdog-ui
5. Integration happens in short, planned merges:
   - Merge Window A: API contracts only
   - Merge Window B: event contracts only
   - Merge Window C: final demo polish

---

## Ownership Matrix

## Person 1: Ingress + EV Simulator (Connection Owner)
Scope:
1. Build separate dummy EV simulator app with cert-based connection.
2. Keep persistent websocket connection alive.
3. Stream charging lifecycle and meter updates.

Owns files:
1. src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/WebSocketConfig.java
2. src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/OcppHandshakeInterceptor.java
3. src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppWebSocketHandler.java
4. EV simulator repo/app (separate project)

Deliverables:
1. EV simulator connects and reconnects automatically.
2. Sends events: boot, heartbeat, tx start, meter update, tx end.
3. Connection identity is mapped to station/EV id.

Checklist:
1. Cert handshake works with dummy EV cert.
2. Heartbeat every fixed interval.
3. Meter updates every 2-5 seconds.
4. Disconnect/reconnect test passes.

---

## Person 2: Trust + Blockchain (Ganache Owner)
Scope:
1. Deploy and maintain FirmwareRegistry on Ganache.
2. Register golden hashes.
3. Verify firmware hashes against chain in live flow.

Owns files:
1. src/main/resources/solidity/FirmwareRegistry.sol
2. src/main/java/com/cybersecuals/gridgarrison/trust/service/BlockchainService.java
3. src/main/java/com/cybersecuals/gridgarrison/trust/service/BlockchainServiceImpl.java
4. src/main/java/com/cybersecuals/gridgarrison/trust/service/BlockchainTrustService.java
5. src/main/resources/application.yml (blockchain config keys only)
6. pom.xml (web3j plugin/dependency section only)

Deliverables:
1. Contract deployed and address configured.
2. Golden hash registration flow works.
3. Verified/tampered outcomes emitted clearly.

Checklist:
1. mvn web3j:generate-sources works.
2. Normal hash returns verified.
3. Mismatch hash returns tampered.
4. Unknown station returns unknown-risk path.

---

## Person 3: Digital Twin + Detection + Visualizer (Response Owner)
Scope:
1. Real-time anomaly logic and scoring.
2. Response policy mapping to station state.
3. Visualizer timeline and control UX.

Owns files:
1. src/main/java/com/cybersecuals/gridgarrison/watchdog/service/DigitalTwinService.java
2. src/main/java/com/cybersecuals/gridgarrison/watchdog/service/DigitalTwinServiceImpl.java
3. src/main/java/com/cybersecuals/gridgarrison/watchdog/service/StationTwin.java
4. src/main/java/com/cybersecuals/gridgarrison/visualizer/RuntimeTraceService.java
5. src/main/java/com/cybersecuals/gridgarrison/visualizer/RuntimeTraceController.java
6. src/main/resources/static/visualizer.html

Deliverables:
1. Rules for energy spike, heartbeat missed, reconnect loop, firmware mismatch correlation.
2. State transitions: MONITORING, SUSPICIOUS, ALERT.
3. One-click scenarios and auto-demo path.

Checklist:
1. Anomaly triggers within a few seconds of event.
2. Response event emitted for each major anomaly.
3. UI reflects trust + watchdog outcomes.
4. Clear/reset/replay path works reliably.

---

## Integration Contracts (shared, edit only in merge windows)

Shared event contracts:
1. Event names and payload schema from orchestrator to trust/watchdog.
2. Trust verdict event structure consumed by watchdog/UI.
3. ActionTaken response format consumed by UI.

Freeze points:
1. Freeze event names before deep implementation.
2. Freeze station state enum before final UI polish.

---

## Timeline Plan

## Day 1
1. P1: EV simulator connection baseline.
2. P2: Ganache deploy + register + verify script.
3. P3: anomaly pipeline baseline + visual timeline validation.

## Day 2
1. P1: robust reconnect + telemetry cadence.
2. P2: live trust integration with simulator input.
3. P3: response policy + station state transitions.

## Day 3
1. End-to-end integration.
2. Auto-demo button/flow.
3. Demo rehearsal and fallback capture.

---

## End-to-End Acceptance Checklist

1. Dummy EV with cert connects and streams continuously.
2. On-chain trust check executes in live path.
3. At least 3 anomaly types trigger expected outcomes.
4. Response action appears in timeline with rationale.
5. Visualizer clearly shows module evidence and station state.
6. Entire demo can be reset and rerun in under 30 seconds.

---

## Demo Sequence (Judge-Friendly)

1. Normal session on station A -> MONITORING.
2. Firmware tamper on station B -> ALERT.
3. Energy anomaly on station C -> SUSPICIOUS (or ALERT if repeated).
4. Show snapshot JSON proving module-by-module evidence.

Outcome statement:
GridGarrison ingests live charging telemetry, verifies trust on-chain, detects anomalies in real time, and emits actionable responses.
