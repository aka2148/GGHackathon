# GridGarrison Hackathon System Guide

This guide is the practical runbook for presenting the live demo end-to-end.

## 1) Demo objective

Show that GridGarrison can:

1. ingest live OCPP-style activity from a station simulator,
2. verify firmware trust against chain/signed baselines,
3. detect suspicious behavior with digital twin telemetry,
4. enforce risk-aware charging outcomes (`RELEASED` vs `REFUNDED`),
5. explain all steps through operator dashboards.

## 2) Runtime components

1. Backend on `https://localhost:8443`
   - visualizer: `/visualizer.html`
   - panel: `/panel.html`

2. Simulator on `http://localhost:8082`
   - dashboard: `/ev-dashboard.html`

3. Ganache RPC (typically `http://127.0.0.1:7545`)

## 3) Startup sequence (demo-mtls)

Terminal A (backend):

```powershell
. .\scripts\local-env.ps1
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Terminal B (simulator):

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Sanity checks:

1. Backend log shows startup on `8443` and trust service contract/rpc loaded.
2. Simulator log shows WebSocket connected and boot accepted.

## 4) Judge-friendly demo script

### Phase 1: Baseline healthy charging

1. Open simulator dashboard (`/ev-dashboard.html`) in user mode.
2. Start guided flow with a valid amount.
3. Show trust gate status as verified.
4. Show escrow lifecycle progression and charging progress.
5. Complete flow and show final settlement `RELEASED`.

### Phase 2: Tamper / trust failure narrative

1. Open panel page (`/panel.html`) and target the same station as simulator.
2. Trigger component tamper / faulty hash generation.
3. Re-run verification or start flow attempt.
4. Show blocked flow or downgraded trust outcome.

### Phase 3: Anomaly/retraction narrative

1. Use simulator dev controls or scenario to generate suspicious telemetry.
2. Show watchdog severity and twin state changes.
3. Show user-flow alert and refund-oriented terminal handling when applicable (`REFUNDED`).

## 5) API probes for backup demo path

If browser UI fails, use API calls directly.

```powershell
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/status"
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/reset?clearIntent=true&resetWallet=false&resetBattery=false"
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/start?inputMode=MONEY&inputValue=20&targetSoc=80"
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/user/flow/status?refreshTrust=true"
```

## 6) Key talking points for judges

1. Identity enforcement is done at handshake (certificate CN and station ID matching in demo-mtls).
2. Trust decisioning is explicit and evidence-backed, not inferred heuristics.
3. Watchdog behavior is deterministic enough for repeatable storytelling.
4. Escrow and settlement state is observable and auditable from UI and API.
5. The system can explain both success and failure modes without hidden steps.

## 7) Known failure modes and mitigations

1. Port collisions (`8443`/`8082`)
   - symptom: startup exits, stale logs, inconsistent UI.
   - mitigation: free ports before demo run.

2. Missing local env contract values
   - symptom: trust RPC/contract errors, verification gate fails.
   - mitigation: always source `scripts/local-env.ps1` before backend startup.

3. Station mismatch between tools
   - symptom: tamper in panel does not affect simulator verification.
   - mitigation: ensure panel station matches simulator station.

4. Protocol mismatch
   - symptom: TLS/handshake errors.
   - mitigation: run both services on same profile (`demo-mtls` recommended).

## 8) Current limitations (explicit)

1. Data plane is simulated, not hardware-device integrated.
2. Actioning is policy/state-driven demo behavior, not full remote charger command control.
3. Production cert lifecycle and secret management are not fully hardened in this demo repo.

These constraints are acceptable for hackathon objectives and still demonstrate realistic security architecture and response logic.
