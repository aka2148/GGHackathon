# GridGarrison EV Simulator

Dummy EV/charging station simulator that connects to GridGarrison over OCPP 2.0.1 WebSocket and emits demo traffic.

## What it supports

- OCPP messages: `BootNotification`, `Heartbeat`, `TransactionEvent`, `FirmwareStatusNotification`
- ISO-15118 bridge context inside transaction payloads (`authorizationMode`, `contractCertificateId`, `seccId`)
- Auto reconnection with exponential backoff
- Runtime telemetry profiles (`normal`, `fast`)
- Manual REST controls for connect/disconnect, transactions, firmware status, and scenarios
- Optional TLS/mTLS support for `wss://` gateway URIs

## Prerequisites

- Java 17+
- Maven 3.8+
- GridGarrison backend running (default endpoint used by simulator is `ws://localhost:8443/ocpp/{stationId}`)

## Build and run

```bash
cd GGHackathon/ev-simulator/simulator-app
mvn -DskipTests clean compile
```

Default run (simulator API on 8080):

```bash
mvn spring-boot:run
```

Run in explicit non-TLS mode (matches backend `dev-ws`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev-ws
```

Run in explicit mTLS mode (matches backend `demo-mtls`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo-mtls
```

Run on 8082 (useful if 8080 is busy on Windows):

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

Run with automatic scheduled scenarios:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--ev.simulator.auto-scenarios=true"
```

## Simulator tests

Run all simulator-module tests:

```bash
mvn test
```

Run only the simulator controller contract tests:

```bash
mvn -Dtest=EvSimulatorControllerTest test
```

## Key configuration

Edit `src/main/resources/application.yml`:

```yaml
ev:
  simulator:
    station-id: CS-101-EV
    gateway-uri: ws://localhost:8443/ocpp/{stationId}
    heartbeat-interval-ms: 30000
    auto-scenarios: false
    reconnect:
      enabled: true
      initial-delay-ms: 1000
      max-delay-ms: 30000
      multiplier: 2.0
    telemetry:
      active-profile: normal
      profiles:
        normal:
          heartbeat-interval-ms: 30000
          meter-update-interval-ms: 10000
          session-duration-ms: 60000
          energy-per-update-kwh: 1.5
        fast:
          heartbeat-interval-ms: 10000
          meter-update-interval-ms: 3000
          session-duration-ms: 30000
          energy-per-update-kwh: 0.8
    tls:
      enabled: false
```

For `wss://` targets, configure `ev.simulator.tls.enabled=true` and set keystore/truststore fields under `ev.simulator.tls`.

## Verified local mTLS checks

Use matching profiles to avoid `ws://` vs `wss://` mismatch:

1) Backend terminal:

```powershell
Set-Location C:/Users/jujhar/Videos/GGHackathon
mvn spring-boot:run -Dspring-boot.run.profiles=demo-mtls
```

2) Simulator terminal (positive CN match expected to connect):

```powershell
Set-Location C:/Users/jujhar/Videos/GGHackathon/ev-simulator/simulator-app
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls -Dspring-boot.run.arguments=--server.port=8082 --ev.simulator.station-id=CS-101-EV"
```

3) Negative check with mismatched station ID (expected handshake rejection):

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls -Dspring-boot.run.arguments=--server.port=8090 --ev.simulator.station-id=CS-999-EV"
```

Expected negative outcome: handshake fails (`Response code was not 101`) and no upgrade is established for the mismatched station ID.

## REST API

Base URL: `http://localhost:8080/api/ev` (or 8082 if overridden)

- `GET /status`
- `POST /connect`
- `POST /disconnect`
- `GET /profiles`
- `POST /profile?name=normal|fast`
- `POST /transaction/start[?transactionId=...]`
- `POST /transaction/meter?kwh=... [&transactionId=...]`
- `POST /transaction/end?totalKwh=... [&transactionId=...]`
- `POST /firmware/status?status=Downloaded&hash=abc123...`
- `GET /scenario/status`
- `POST /scenario/run?name=normalCharging|firmwareTamper|reconnectLoop`
- `POST /scenario/stop`

Behavior notes:

- `GET /status` returns `stationId`, `connected`, `activeTransactionId`, and `activeProfile`.
- `POST /scenario/run` accepts `normalCharging`, `firmwareTamper`, `reconnectLoop`, `isoPnCHappyPath`, and `isoPnCCertMissing`; unknown names return `400`.
- `POST /firmware/status?status=...` maps `status` into the OCPP payload field `status`.
- Backend validates ISO-15118 policy hints: `PlugAndCharge` mode requires `contractCertificateId`.

## Web dashboard

The simulator now includes an interactive dashboard that uses all implemented simulator APIs.

Open in browser after starting simulator:

- `http://localhost:8080/ev-dashboard.html`

What you can do from the dashboard:

- Follow a guided 5-step demo flow (connect → profile → start → stream energy → end)
- View dynamic EV visuals (animated EV model, charging beam, wheel motion)
- Monitor live telemetry-style metrics (battery %, temperature, speed, charge power)
- Connect/disconnect WebSocket session
- View live status (station ID, connection, active transaction, active profile, scenario)
- Switch telemetry profile (`normal` / `fast`)
- Start/meter-update/end transactions
- Send firmware status notifications
- Run/stop scenarios (`normalCharging`, `firmwareTamper`, `reconnectLoop`, `isoPnCHappyPath`, `isoPnCCertMissing`)
- Watch API responses and EV event log in real time

## Multi-EV demo orchestration (Day 3)

Use the helper scripts under `ev-simulator/scripts` to run multiple simulator instances in parallel.

Launch 3 EV simulators (ports 8080-8082):

```powershell
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\scripts
.\launch-multi-ev.ps1 -Count 3 -BasePort 8080 -StationPrefix CS-10
```

This starts stations like `CS-101-EV`, `CS-102-EV`, `CS-103-EV` and stores PID/log info in `scripts/.run/multi-ev-pids.json`.

Stop all launched simulators:

```powershell
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\scripts
.\stop-multi-ev.ps1
```

PowerShell examples:

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/status" -Method Get
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/profile?name=fast" -Method Post
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/run?name=normalCharging" -Method Post
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/stop" -Method Post
```

## Troubleshooting

- `mvn clean compile` succeeds but `mvn spring-boot:run` fails: verify runtime `JAVA_HOME` is Java 17 and Maven uses the same JDK.
- Port 8080 conflicts are common on Windows dev machines; run simulator with `--server.port=8082`.
- If not connecting to backend, confirm backend is listening on 8443 and simulator `gateway-uri` points to `/ocpp/{stationId}`.
- For `wss://` endpoints, check TLS store paths/passwords and ensure certificates are valid.
