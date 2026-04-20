# VERIFY SETUP (Person 1)

Use this checklist to validate current backend + simulator integration.

## 1) Pre-flight

```powershell
java -version
mvn -version
```

Expected:

1. Java 17+
2. Maven 3.8+

## 2) Build verification

From repo root:

```powershell
mvn -DskipTests compile
mvn -f ev-simulator/simulator-app/pom.xml -DskipTests compile
```

Expected: both commands end with `BUILD SUCCESS`.

## 3) Runtime verification (recommended demo-mtls path)

### Terminal 1 — backend

```powershell
. .\scripts\local-env.ps1
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Expected logs include:

1. `Tomcat started on port 8443 (https)`
2. trust/blockchain initialization without fatal contract errors

### Terminal 2 — simulator

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

Expected logs include:

1. `Connecting to wss://localhost:8443/ocpp/EV-Simulator-001`
2. `WebSocket opened`
3. boot accepted and heartbeat activity

### Terminal 3 — API smoke

```powershell
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/status" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/reset?clearIntent=true&resetWallet=false&resetBattery=false" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Get  -Uri "http://localhost:8082/api/ev/user/flow/status?refreshTrust=true" | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/ev/user/flow/start?inputMode=MONEY&inputValue=20&targetSoc=80" | ConvertTo-Json -Depth 8
```

Expected:

1. status shows simulator connected and runtime profile details.
2. flow status after reset reports escrow `NOT_CREATED`.
3. flow start returns `ok: true`, trust gate `VERIFIED`, and a non-empty escrow address.

## 4) Optional dev-ws verification

Backend:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev-ws"
```

Simulator:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=dev-ws"
```

This path is useful for websocket-only debugging without TLS cert concerns.

## 5) Identity mapping checks (demo-mtls)

Positive case:

1. station ID remains `EV-Simulator-001`
2. websocket connects successfully

Negative case:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls -Dspring-boot.run.arguments=--server.port=8090 --ev.simulator.station-id=CS-999"
```

Expected:

1. simulator handshake fails (`Response code was not 101`).
2. backend does not accept upgrade for mismatched station ID.

## 6) Troubleshooting

1. Port conflict on `8443` or `8082`
	- stop stale Java processes before rerun.

2. Guided flow blocked with contract/RPC errors
	- ensure backend started after sourcing `scripts/local-env.ps1`.

3. Panel tamper not affecting simulator
	- align panel station selection with simulator station.

4. `wss://` trust failures
	- verify simulator `demo-mtls` cert/truststore settings in `application.yml`.
