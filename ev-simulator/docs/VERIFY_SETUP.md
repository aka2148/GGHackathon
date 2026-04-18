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
mvn spring-boot:run -Dspring-boot.run.profiles=dev-ws
```

Expected logs include:
- `Tomcat started on port 8443`
- `Started GridGarrisonApplication`

### Terminal 2 — simulator (default 8080; use 8082 if 8080 is busy)

```powershell
cd c:\Users\jujhar\Videos\GGHackathon\ev-simulator\simulator-app
mvn spring-boot:run "-Dspring-boot.run.profiles=dev-ws -Dspring-boot.run.arguments=--server.port=8082"
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

Enable mTLS at runtime (deterministic local flow):

```powershell
# backend
mvn spring-boot:run -Dspring-boot.run.profiles=demo-mtls

# simulator terminal
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls -Dspring-boot.run.arguments=--server.port=8082 --ev.simulator.station-id=CS-101-EV"
```

### Identity-mapping checks (CN to stationId)

Positive case (should connect):
- Keep station ID `CS-101-EV` (matches current client cert CN).
- Expected backend logs include `Station connected` and `BootNotification` for `CS-101-EV`.

Negative case (should be rejected during handshake):

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=demo-mtls -Dspring-boot.run.arguments=--server.port=8090 --ev.simulator.station-id=CS-999-EV"
```

Expected:
- simulator shows handshake failure (`Response code was not 101`).
- no WebSocket upgrade for `CS-999-EV`.

## Troubleshooting

- `keytool not recognized`: ensure Java 17 `bin` is on PATH.
- Port conflict on 8080: run simulator on 8082.
- Backend run shows exit code `1` in one terminal while app still works elsewhere: check active listener process and endpoint probes instead of relying only on terminal status.
- If `wss://` fails with trust errors, verify `JAVA_TOOL_OPTIONS` truststore path points to `ev-simulator/simulator-app/src/main/resources/certs/ca-trust.p12`.
