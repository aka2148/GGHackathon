# PERSON 1 CHECKLIST (Updated)

## Completed checklist

- [x] WebSocket ingress ownership files are implemented and active.
- [x] Simulator app exists under `ev-simulator/simulator-app`.
- [x] OCPP events implemented (boot, heartbeat, transaction lifecycle, firmware status).
- [x] Reconnect backoff implemented.
- [x] Manual API controls implemented (`/api/ev/status`, `/connect`, `/disconnect`, transaction, firmware).
- [x] Scenario API implemented (`/scenario/run`, `/scenario/status`, `/scenario/stop`).
- [x] Telemetry profiles implemented (`normal`, `fast`) with runtime switching.
- [x] Optional simulator TLS/mTLS path implemented for `wss://`.
- [x] Local dev certs generated for backend and simulator resource paths.
- [x] Certificate identity mapping enforced in backend handshake (`CN == stationId` when client cert is present).

## Remaining checklist

- [ ] Replace local self-signed certs with team-managed/prod-safe cert flow.
- [x] Add optional multi-EV simulator orchestration for Day 3 demo stress path.

## Fast run commands

### Build

```powershell
cd c:\Users\jujhar\Videos\GGHackathon
mvn -DskipTests clean compile

cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
mvn -DskipTests clean compile
```

### Run (non-TLS)

```powershell
# backend
cd c:\Users\jujhar\Videos\GGHackathon
mvn spring-boot:run

# simulator (default 8080; use 8082 if 8080 conflict)
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

### Scenario smoke

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/run?name=reconnectLoop" -Method Post
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/stop" -Method Post
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/status" -Method Get
```

### Multi-EV (Day 3 demo stress path)

```powershell
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\scripts
.\launch-multi-ev.ps1 -Count 3 -BasePort 8080 -StationPrefix CS-10

# when done
.\stop-multi-ev.ps1
```

### Optional mTLS run

```powershell
# backend terminal
$env:GG_SSL_ENABLED='true'; $env:GG_CLIENT_AUTH='need'; mvn spring-boot:run

# simulator terminal
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080 --ev.simulator.gateway-uri=wss://localhost:8443/ocpp/{stationId} --ev.simulator.tls.enabled=true"
```
