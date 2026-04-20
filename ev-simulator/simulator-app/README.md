# GridGarrison EV Simulator

The simulator is a standalone Spring Boot app that acts as a demo EV/station client for GridGarrison.

## What it supports

1. OCPP-style event traffic
  - `BootNotification`
  - `Heartbeat`
  - `TransactionEvent` (start/update/end)
  - `FirmwareStatusNotification`

2. Profile-driven transport
  - `dev-ws`: plain websocket
  - `demo-mtls`: TLS websocket with cert identity checks

3. Guided user flow
  - charge intent and escrow-backed charging simulation
  - trust gate verification before charging begins
  - settlement/release or refund outcomes

4. Digital twin controls
  - telemetry forwarding
  - control overrides for power/temperature/rate multiplier

## Defaults in current code

- simulator API port: `8082`
- default station ID: `CS-101`
- `demo-mtls` station ID override: `EV-Simulator-001`
- default gateway URI: `ws://localhost:8443/ocpp/{stationId}`

## Build and run

From repository root:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml -DskipTests compile
```

Run non-TLS profile:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=dev-ws"
```

Run demo mTLS profile:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml spring-boot:run "-Dspring-boot.run.profiles=demo-mtls"
```

## Prerequisites

1. Java 17+
2. Maven 3.8+
3. Backend running on port `8443`
4. For guided trust/escrow paths: backend started with local env (`scripts/local-env.ps1`)

## Dashboard

Open:

- `http://localhost:8082/ev-dashboard.html`

Main modes:

1. User mode
  - wallet + charge input
  - payment window and milestone progression
  - guided flow start/complete/reset

2. Dev mode
  - manual connect/disconnect
  - direct transaction controls
  - firmware status pushes
  - scenarios and digital twin controls

## API summary

Base URL:

- `http://localhost:8082/api/ev`

Core status and connection:

- `GET /status`
- `POST /connect`
- `POST /disconnect`

Profiles and scenarios:

- `GET /profiles`
- `POST /profile?name=normal|fast`
- `GET /scenario/status`
- `POST /scenario/run?name=...`
- `POST /scenario/stop`

Manual transaction controls:

- `POST /transaction/start`
- `POST /transaction/meter`
- `POST /transaction/end`

User flow controls:

- `POST /user/intent`
- `POST /user/flow/start`
- `GET /user/flow/status?refreshTrust=true`
- `POST /user/flow/complete`
- `POST /user/flow/reset?clearIntent=true&resetWallet=false&resetBattery=false`

Wallet controls:

- `GET /user/wallet`
- `POST /user/wallet/topup`

Digital twin dev controls:

- `GET /dev/digital-twin/status`
- `POST /dev/digital-twin/controls`

## Configuration notes

File:

- `src/main/resources/application.yml`

Important keys:

1. `ev.simulator.station-id`
2. `ev.simulator.gateway-uri`
3. `ev.simulator.verify.backend-base-url`
4. `ev.simulator.tls.*`
5. `ev.simulator.user.*` (wallet/pricing/polling/auto-charge)

Critical `demo-mtls` setting:

- `ev.simulator.verify.backend-base-url: https://localhost:8443`

## mTLS behavior

In `demo-mtls` profile:

1. simulator connects to `wss://localhost:8443/ocpp/{stationId}`
2. simulator uses `ev-sim.jks` and truststore configured in `application.yml`
3. backend handshake enforces CN/stationId mapping

If station ID does not match cert CN, websocket upgrade is rejected.

## Tests

Run simulator tests:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml test
```

Run controller slice tests only:

```powershell
mvn -f ev-simulator/simulator-app/pom.xml -Dtest=EvSimulatorControllerTest test
```

## Troubleshooting

1. Startup fails with port in use
  - free `8082` or run with a temporary override.

2. Connection fails in demo-mtls
  - verify backend is also on `demo-mtls`.
  - verify station ID and cert CN alignment.

3. Guided flow blocked before charging
  - ensure backend has `GG_CONTRACT_ADDR`, wallet key, and RPC from `scripts/local-env.ps1`.

4. Tamper from panel appears ineffective
  - ensure panel station selection matches simulator station.
