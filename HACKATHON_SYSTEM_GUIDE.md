# GridGarrison: Current Working Architecture and Next Steps

## 1. What The Application Currently Does

GridGarrison is currently a simulated EV charging security platform that demonstrates:

1. OCPP-like station event ingestion through the orchestrator module.
2. Firmware trust verification flow using blockchain concepts (Ganache-compatible setup).
3. Digital twin state tracking with anomaly classification.
4. A live visualizer UI that shows event timeline, module activity, and station state transitions.

This is already enough for an end-to-end hackathon demo with deterministic outcomes.

---

## 2. Current Module Responsibilities

### Orchestrator module

Purpose:
- Receives station-facing protocol events.
- Publishes internal events for downstream modules.

Current behavior in simulation:
- Emits events such as `StationBootEvent`, `TransactionEvent`, and `FirmwareStatusEvent`.

### Trust module

Purpose:
- Compares reported firmware hash against an authoritative golden hash.
- Produces trust outcomes (verified vs tampered).

Current behavior in simulation:
- Emits `GoldenHashVerifiedEvent` for normal flows.
- Emits `GoldenHashTamperedEvent` for tamper scenarios.

Blockchain setup status:
- Web3j connection defaults to Ganache GUI port `7545`.
- Solidity contract source exists at `src/main/resources/solidity/FirmwareRegistry.sol`.
- Web3j wrapper generation is configured in Maven.

### Watchdog (Digital Twin) module

Purpose:
- Keeps station-level digital twin state.
- Detects anomaly patterns and escalates operational state.

Current behavior in simulation:
- Tracks twin registration and session updates.
- Emits anomaly-style events (for example energy spike or firmware mismatch).

### Visualizer module

Purpose:
- Converts backend event stream into an operator-facing real-time dashboard.
- Shows station statuses (`MONITORING`, `SUSPICIOUS`, `ALERT`) and timeline context.

---

## 3. How Frontend Connects to Backend

Frontend file:
- `src/main/resources/static/visualizer.html`

This is a static page served by Spring Boot. The page polls backend JSON APIs every ~2.5 seconds and supports button-triggered scenario actions.

### Backend APIs used by frontend

Base path:
- `/visualizer/api`

Read endpoints:
1. `GET /visualizer/api/snapshot`
   - Returns current aggregate state:
     - total events
     - events by type
     - events by module
     - station list + operational state

2. `GET /visualizer/api/events?limit=80`
   - Returns recent timeline events in reverse chronological order.

Action endpoints:
1. `POST /visualizer/api/simulate-normal?stationId=...`
2. `POST /visualizer/api/simulate-tamper?stationId=...`
3. `POST /visualizer/api/simulate-anomaly?stationId=...`
4. `DELETE /visualizer/api/events`
   - Clears runtime timeline/state for clean reruns.

### UI to API flow

1. User clicks a scenario button in visualizer.
2. Browser sends a POST request to the corresponding simulation endpoint.
3. Backend appends orchestrator/trust/watchdog/app events.
4. Frontend polling fetches updated snapshot/events and rerenders UI.

---

## 4. How Action Is Currently Being Taken

Action in current implementation means **state and policy signaling** (not physical charger control yet).

Current action chain:

1. Scenario or input events are appended.
2. Trust + anomaly outcomes are emitted (for example tampered firmware).
3. App-level `ActionTakenEvent` is emitted.
4. Station operational state is computed and displayed:
   - `MONITORING` for normal / low-risk flow.
   - `SUSPICIOUS` for behavior anomalies.
   - `ALERT` for tamper/security-critical outcomes.

This is the right stage for hackathon: visible, deterministic decisioning without hardware dependencies.

---

## 5. How To Run and Demo It

### Start backend

```powershell
mvn spring-boot:run
```

### Open UI

- `http://localhost:8443/visualizer.html`

### Trigger scenarios from terminal (optional)

```powershell
# Reset
Invoke-WebRequest -Method Delete -Uri "http://localhost:8443/visualizer/api/events" -UseBasicParsing | Out-Null

# Normal
Invoke-WebRequest -Method Post -Uri "http://localhost:8443/visualizer/api/simulate-normal?stationId=CS-101" -UseBasicParsing | Select-Object -ExpandProperty Content

# Tamper
Invoke-WebRequest -Method Post -Uri "http://localhost:8443/visualizer/api/simulate-tamper?stationId=CS-102" -UseBasicParsing | Select-Object -ExpandProperty Content

# Anomaly
Invoke-WebRequest -Method Post -Uri "http://localhost:8443/visualizer/api/simulate-anomaly?stationId=CS-103" -UseBasicParsing | Select-Object -ExpandProperty Content
```

---

## 6. Future Plan (Roadmap)

## Phase A: Demo Hardening (short-term, hackathon critical)

1. Add one-click "Auto Demo" sequence (Normal -> Tamper -> Anomaly).
2. Add scenario timestamps/labels in UI for storytelling.
3. Add a small health panel:
   - backend up/down
   - Ganache RPC reachable/unreachable
4. Add deterministic replay profiles for judges.

## Phase B: Near-Real Security Simulation

1. Replace purely synthetic scenario generation with payload-driven simulation.
2. Introduce a station simulator process that emits realistic OCPP frame patterns.
3. Add firmware hash registration + verification flows against deployed Ganache contract addresses.
4. Store scenario outputs (JSON) for reproducibility.

## Phase C: Real-Time Anomaly Pipeline

1. Add streaming ingestion abstraction (event queue / topic).
2. Add rule engine with threshold + pattern windows.
3. Add event-time processing for latency-aware detection.
4. Emit confidence score and severity per anomaly.

## Phase D: Production Readiness (post-hackathon)

1. Strict mTLS identity binding and cert lifecycle.
2. Persistent audit/event store.
3. Alert integrations (SIEM, webhook, email, SOC dashboard).
4. Policy actions beyond signaling (soft isolate / command blocking / quarantine workflows).

---

## 7. How To Simulate Actual Attacks

These attack simulations can be implemented with current architecture and visualizer quickly.

### Attack 1: Firmware Tampering

Simulation idea:
1. Register known golden hash for station in Ganache contract.
2. Send `FirmwareStatusEvent` with a mismatched live hash.

Expected detection:
- Trust emits tamper event.
- Watchdog flags firmware mismatch anomaly.
- Station becomes `ALERT`.

### Attack 2: Energy Abuse / Meter Manipulation Pattern

Simulation idea:
1. Send normal baseline transaction updates.
2. Send sudden high energy spike (for example >3x expected).

Expected detection:
- Watchdog emits `EnergySpikeAnomalyEvent`.
- Station becomes `SUSPICIOUS` (or `ALERT` if repeated).

### Attack 3: Boot Loop / Rapid Reconnect

Simulation idea:
1. Emit repeated `StationBootEvent` in short intervals.
2. Optionally mix with heartbeat drops.

Expected detection:
- Rapid reconnect anomaly event.
- Escalation from `MONITORING` -> `SUSPICIOUS` -> `ALERT` depending on threshold.

### Attack 4: Unknown Station Identity

Simulation idea:
1. Emit events for station IDs with no registered trust profile.

Expected detection:
- Trust status: unknown/failed verification path.
- Watchdog marks elevated risk until identity is validated.

---

## 8. How To Detect Real-Time Anomalies Better

To move from deterministic demo to realistic real-time detection:

1. Use sliding windows per station:
   - last N transactions
   - last M minutes
2. Track baseline features:
   - average kWh/session
   - variance
   - session duration
   - transaction frequency
3. Compute anomaly score:
   - score = weighted sum of rule violations
4. Introduce severity bands:
   - low (monitor)
   - medium (suspicious)
   - high (alert)
5. Add correlation rules:
   - tamper + abnormal energy + reconnect loops => immediate alert.

---

## 9. Suggested Hackathon Narrative

Use this sequence in your live demo:

1. Run `simulate-normal` for station A, show `MONITORING`.
2. Run `simulate-tamper` for station B, show trust mismatch and `ALERT`.
3. Run `simulate-anomaly` for station C, show behavior anomaly and `SUSPICIOUS`.
4. Show snapshot JSON proving module-level and station-level evidence.

Outcome message:
- GridGarrison does not just ingest events.
- It classifies trust and behavior risk in real time and surfaces actionable operational state.

---

## 10. Current Limitations (Explicit)

1. Station inputs are simulated, not hardware-derived.
2. Action execution is currently policy signaling, not device enforcement.
3. Some rule logic is deterministic for demo clarity.
4. Full production identity/security controls are intentionally deferred.

These are acceptable for a hackathon and still demonstrate a strong architecture and clear path to production.
