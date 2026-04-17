# VERIFY SETUP (Person 1)

Use this checklist to validate the current GridGarrison + EV simulator integration.

## 1) Pre-flight

```powershell
java -version
mvn -version
```

Expected:
- Java 17+
- Maven 3.8+

## 2) Build verification

```powershell
cd c:\Users\jujhar\Videos\GGHackathon
mvn -DskipTests clean compile

cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
mvn -DskipTests clean compile
```

Expected: both commands end with `BUILD SUCCESS`.

## 3) Runtime verification (non-TLS dev path)

### Terminal 1 — backend

```powershell
cd c:\Users\jujhar\Videos\GGHackathon
mvn spring-boot:run
```

Expected logs include:
- `Tomcat started on port 8443`
- `Started GridGarrisonApplication`

### Terminal 2 — simulator (default 8080; use 8082 if 8080 is busy)

```powershell
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

Expected logs include:
- `Connected to GridGarrison`
- Boot accepted and heartbeat loop

### Terminal 3 — API smoke

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/status" -Method Get | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/run?name=reconnectLoop" -Method Post | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/stop" -Method Post | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri "http://localhost:8080/api/ev/scenario/status" -Method Get | ConvertTo-Json -Depth 8
```

Expected:
- status shows `connected: true`
- run returns `ok: true`
- stop returns `ok: true` when active, `400` if already finished

## 4) Optional mTLS verification

Generated local dev cert artifacts:
- Backend: `src/main/resources/certs/gridgarrison-server.p12`, `src/main/resources/certs/gridgarrison-ca-trust.p12`
- Simulator: `ev-simulator/simulator-app/src/main/resources/certs/client.p12`, `ev-simulator/simulator-app/src/main/resources/certs/ca-trust.p12`

Enable mTLS at runtime:

```powershell
# backend
$env:GG_SSL_ENABLED='true'; $env:GG_CLIENT_AUTH='need'; mvn spring-boot:run

# simulator (separate terminal)
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080 --ev.simulator.gateway-uri=wss://localhost:8443/ocpp/{stationId} --ev.simulator.tls.enabled=true"
```

## Troubleshooting

- `keytool not recognized`: ensure Java 17 `bin` is on PATH.
- Port conflict on 8080: run simulator on 8082.
- Backend run shows exit code `1` in one terminal while app still works elsewhere: check active listener process and endpoint probes instead of relying only on terminal status.
