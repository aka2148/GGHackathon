# GridGarrison EV Simulator

Dummy EV/charging station simulator that connects to GridGarrison over OCPP 2.0.1 WebSocket and emits demo traffic.

## What it supports

- OCPP messages: `BootNotification`, `Heartbeat`, `TransactionEvent`, `FirmwareStatusNotification`
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

Run on 8082 (useful if 8080 is busy on Windows):

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

Run with automatic scheduled scenarios:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--ev.simulator.auto-scenarios=true"
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
