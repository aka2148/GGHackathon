# GridGarrison

EV charging trust and anomaly demo platform built with Spring Boot, Spring Modulith, Web3j, and a separate EV simulator app.

## What this repository contains

The workspace has two runnable applications:

1. Backend (this root Maven project)
    - OCPP WebSocket ingress at `/ocpp/{stationId}`
    - mTLS-aware handshake and station identity checks
    - trust verification and escrow lifecycle integration
    - digital twin + anomaly detection
    - visualizer and operator control pages

2. EV simulator (`ev-simulator/simulator-app`)
    - persistent OCPP client (ws/wss)
    - guided user charging flow and payment/escrow window
    - firmware verification and watchdog telemetry forwarding
    - dashboard UI at `/ev-dashboard.html`

## Architecture summary

```text
com.cybersecuals.gridgarrison
├── orchestrator  (OCPP gateway, WebSocket security, event publishing)
├── trust         (golden hash verification, blockchain, escrow APIs)
├── watchdog      (digital twin + anomaly detection)
├── visualizer    (runtime timeline, station snapshots, panel controls)
└── shared        (minimal DTOs)
```

Modules communicate through Spring application events rather than direct cross-module implementation calls.

## Current demo flows

1. OCPP connect and telemetry
    - simulator connects over `ws://` (dev-ws) or `wss://` (demo-mtls)
    - boot, heartbeat, transaction, firmware events stream into backend

2. Firmware trust verification
    - backend compares reported/generate hash to on-chain or signed baseline
    - verdicts: `VERIFIED`, `TAMPERED`, or unresolved failure states

3. User charging + escrow lifecycle
    - simulator guided flow creates intent, verifies trust, waits for escrow `AUTHORIZED`, starts charging, then settles
    - escrow terminal outcomes include `RELEASED` and `REFUNDED`

4. Digital twin response
    - watchdog consumes telemetry and station state
    - anomalies can quarantine/retract sessions and drive refund workflows

## Quick start (Windows, current workspace)

### 1) One-time local env setup

```powershell
Copy-Item scripts/local-env.ps1.example scripts/local-env.ps1
```

Edit `scripts/local-env.ps1` with your Ganache key/address values.

### 2) Start backend with env loaded

Recommended local run:

```powershell
. .\scripts\local-env.ps1
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Backend URL:

- `https://localhost:8443`

### 3) Start simulator

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Simulator URL:

- `http://localhost:8082/ev-dashboard.html`

## Profiles and identity behavior

### `dev-ws`

- backend SSL disabled
- simulator gateway uses `ws://localhost:8443/ocpp/{stationId}`

### `demo-mtls`

- backend SSL enabled at `8443`
- simulator gateway uses `wss://localhost:8443/ocpp/{stationId}`
- simulator verify API base URL must be `https://localhost:8443`
- current demo certificate flow expects station ID `EV-Simulator-001`

Important: OCPP handshake enforces station identity with certificate CN matching station ID in demo-mtls.

## Trust and escrow prerequisites

For guided charging flows to work reliably:

1. Ganache RPC must be reachable.
2. `GG_WALLET_KEY` must be set in `scripts/local-env.ps1`.
3. `GG_CONTRACT_ADDR` must point to a deployed contract.

If these are missing, verification may fail with contract/RPC errors and user flow start will be blocked.

## Useful commands

Build backend:

```powershell
mvn -DskipTests compile
```

Build simulator:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml -DskipTests compile
```

Simulator controller tests:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml -Dtest=EvSimulatorControllerTest test
```

## Common pitfalls

1. Port conflicts on `8443` or `8082`
    - stale Java processes can make startup look flaky.

2. Wrong Maven working directory
    - `mvn spring-boot:run` from repo root starts backend, not simulator.
    - use `-f ev-simulator/simulator-app/pom.xml` for simulator commands.

3. Protocol mismatch in demo-mtls
    - simulator verification against `http://localhost:8443` fails; use `https://localhost:8443`.

4. Station mismatch across tools
    - panel tamper/verify actions must target the same station ID as simulator (demo-mtls station is `EV-Simulator-001`).

## Environment variables (backend)

| Variable | Purpose | Default |
|---|---|---|
| `GG_RPC_URL` | Ethereum RPC endpoint | `http://127.0.0.1:7545` |
| `GG_WALLET_KEY` | EOA private key used for chain writes | `0x000...001` |
| `GG_CONTRACT_ADDR` | Firmware/trust contract address | zero address |
| `GG_BOOTSTRAP_ENABLED` | deploy/seed on startup | `false` |
| `GG_SSL_ENABLED` | backend SSL toggle | `false` |
| `GG_CLIENT_AUTH` | TLS client auth mode (`want`/`need`) | `want` |
| `GG_VISUALIZER_PUBLIC` | public access to visualizer pages | `true` |

## UI pages

Backend-served pages:

- `https://localhost:8443/visualizer.html`
- `https://localhost:8443/panel.html`

Simulator-served page:

- `http://localhost:8082/ev-dashboard.html`
