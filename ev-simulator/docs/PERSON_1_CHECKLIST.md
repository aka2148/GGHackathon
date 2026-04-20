# PERSON 1 CHECKLIST

## Completed

- [x] OCPP ingress path maintained and active.
- [x] Simulator app integrated under `ev-simulator/simulator-app`.
- [x] OCPP event set implemented: boot, heartbeat, transaction start/update/end, firmware status.
- [x] reconnect/backoff behavior implemented.
- [x] simulator REST API controls for status/connection/transactions/scenarios.
- [x] guided user charging flow implemented (`/user/flow/*`).
- [x] trust gate integration blocks charging when verification fails.
- [x] escrow polling and settlement integration wired into user flow.
- [x] twin telemetry + controls integrated in simulator dashboard.
- [x] `demo-mtls` profile verified with station identity alignment.
- [x] dashboard startup consistency hardening shipped (clean reset + zero-address handling).

## Remaining

- [ ] production certificate lifecycle (managed CA, rotation, secure secret storage).
- [ ] expanded end-to-end smoke automation across happy and anomaly paths.

## Fast commands

### Build

```powershell
mvn -DskipTests compile
mvn -f ev-simulator/simulator-app/pom.xml -DskipTests compile
```

### Run demo-mtls

```powershell
# backend
. .\scripts\local-env.ps1
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"

# simulator
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

### User-flow smoke

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/reset?clearIntent=true&resetWallet=false&resetBattery=false"
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/user/flow/status?refreshTrust=true"
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/start?inputMode=MONEY&inputValue=20&targetSoc=80"
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/user/flow/status?refreshTrust=true"
```

### Scenario smoke

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/scenario/run?name=reconnectLoop"
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/scenario/stop"
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/scenario/status"
```

### Multi-EV helper scripts

```powershell
cd ev-simulator/scripts
.\launch-multi-ev.ps1 -Count 3 -BasePort 8082 -StationPrefix EV-Simulator-
.\stop-multi-ev.ps1
```
