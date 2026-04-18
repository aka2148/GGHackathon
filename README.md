# GridGarrison 🔒⚡

**EV Charging Trust & Identity Platform** — OCPP 2.0.1 · Blockchain · Digital Twin

---

## Architecture

```
com.cybersecuals.gridgarrison
├── orchestrator/          ← OCPP 2.0.1 WSS gateway + mTLS enforcement
│   ├── config/
│   │   ├── WebSocketConfig.java        (public)  OCPP WS endpoint registration
│   │   ├── MtlsSecurityConfig.java     (pkg-priv) Spring Security x509 config
│   │   └── OcppHandshakeInterceptor.java (pkg-priv) Sub-protocol + stationId check
│   └── websocket/
│       ├── OcppWebSocketHandler.java   (public)  Message dispatcher
│       └── OcppMessage.java            (pkg-priv) SRPC frame model + domain events
│
├── trust/                 ← Golden Hash verification via Web3j
│   ├── service/
│   │   ├── BlockchainService.java      (public)  API interface
│   │   └── BlockchainServiceImpl.java  (pkg-priv) Web3j implementation
│   └── contract/
│       └── FirmwareRegistryContract.java (pkg-priv) Web3j contract wrapper
│
├── watchdog/              ← Digital Twin + behavioural anomaly detection
│   └── service/
│       ├── DigitalTwinService.java     (public)  API interface
│       ├── StationTwin.java            (public)  Value objects
│       └── DigitalTwinServiceImpl.java (pkg-priv) In-memory twin engine
│
└── shared/                ← Cross-module DTOs (declared as sharedModule)
    └── dto/
        ├── ChargingSession.java
        └── FirmwareHash.java
```

## Module Communication

Modules communicate exclusively via **Spring application events** — no direct
compile-time imports across module boundaries.

```
orchestrator  ──[StationBootEvent]──►  watchdog  (registers twin)
orchestrator  ──[FirmwareStatusEvent]► trust     (triggers hash verification)
orchestrator  ──[TransactionEvent]───► watchdog  (updates session state)
```

## Key Technologies

| Concern | Technology |
|---|---|
| Transport | Spring WebSocket (WSS / OCPP 2.0.1) |
| Auth | Spring Security x.509 mTLS |
| Blockchain | Web3j → Ethereum smart contract |
| Anomaly Detection | Digital Twin in-memory engine |
| Module Isolation | Spring Modulith |

## Quick Start

```bash
# Build
./mvnw clean package -DskipTests

# Run with H2 (dev mode, no certs required*)
./mvnw spring-boot:run

# Verify module boundaries
./mvnw test -Dtest=ModularityVerificationTest
```

\* For full mTLS, generate certs and place in `src/main/resources/certs/`.

## demo-mtls profile behavior

When running with `SPRING_PROFILES_ACTIVE=demo-mtls`:

- Backend HTTPS still runs on `https://localhost:8443`
- Default TLS client auth is `want` (optional cert at connector level)
- OCPP endpoint `/ocpp/**` still enforces station auth and CN/stationId checks
- Visualizer/UI can open without browser client certificate

To force strict connector-level client certs for all HTTPS requests, set:

```powershell
$env:GG_CLIENT_AUTH = "need"
```

For demo-friendly behavior, keep:

```powershell
$env:GG_CLIENT_AUTH = "want"
```

## mTLS handshake tests

Automated handshake verification lives in:

- `src/test/java/com/cybersecuals/gridgarrison/orchestrator/config/OcppHandshakeInterceptorTest.java`

Covered scenarios:

- accepts when `Sec-WebSocket-Protocol` includes `ocpp2.0.1` and certificate CN matches `/ocpp/{stationId}`
- rejects when CN and `stationId` mismatch
- rejects when required OCPP subprotocol is missing
- rejects when client certificate subject has no CN

Run only this test class:

```powershell
mvn -Dtest=OcppHandshakeInterceptorTest test
```

## Local Env Setup

If you do not want to retype Ganache variables every session, use the helper files under `scripts/`:

```powershell
Copy-Item scripts/local-env.ps1.example scripts/local-env.ps1
```

Edit `scripts/local-env.ps1` once, then start the app with:

```powershell
.
scripts\start-local.ps1
```

Use `-Bootstrap` the first time if you want the app to deploy and seed the contract automatically:

```powershell
.
scripts\start-local.ps1 -Bootstrap
```

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `GG_RPC_URL` | Ethereum JSON-RPC endpoint | `http://localhost:8545` |
| `GG_WALLET_KEY` | Admin wallet private key | `0x000...1` |
| `GG_CONTRACT_ADDR` | FirmwareRegistry contract address | `0x000...0` |
| `GG_BOOTSTRAP_ENABLED` | Deploy and seed contract at startup | `false` |
| `GG_SEED_GOLDEN_HASHES` | Seed station hashes map | `{}` |
| `GG_DB_URL` | JDBC URL | H2 in-memory |
| `GG_SERVER_KS_PASSWORD` | Server keystore password | `changeit` |
| `GG_CA_TS_PASSWORD` | CA truststore password | `changeit` |
| `GG_CLIENT_AUTH` | TLS client-auth mode (`want` or `need`) | `want` |

## Ganache Bootstrap (Deploy + Seed)

Use this once after Ganache starts to deploy `FirmwareRegistry` and seed known station hashes.

PowerShell example:

```powershell
$env:GG_RPC_URL = "http://127.0.0.1:7545"
$env:GG_WALLET_KEY = "<private-key-of-ganache-account-0>"
$env:GG_BOOTSTRAP_ENABLED = "true"
$env:GG_SEED_GOLDEN_HASHES = "{'CS-101':'0xabc123','CS-102':'0xdef456'}"
$env:GG_CONTRACT_ADDR = "0x0000000000000000000000000000000000000000"
mvn spring-boot:run
```

Startup logs will print the deployed contract address:

```text
[Bootstrap] Deployed contract address=0x...
[Bootstrap] Set GG_CONTRACT_ADDR=0x... for future runs
```

For normal runs after bootstrap, set:

- `GG_BOOTSTRAP_ENABLED=false`
- `GG_CONTRACT_ADDR=<deployed-address>`
