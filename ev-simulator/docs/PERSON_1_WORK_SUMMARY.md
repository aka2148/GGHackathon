# PERSON 1 WORK SUMMARY (Current)

## Scope owned

- Backend ingress files:
  - `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/WebSocketConfig.java`
  - `src/main/java/com/cybersecuals/gridgarrison/orchestrator/config/OcppHandshakeInterceptor.java`
  - `src/main/java/com/cybersecuals/gridgarrison/orchestrator/websocket/OcppWebSocketHandler.java`
- Simulator module:
  - `ev-simulator/simulator-app/**`

## Completed

- WebSocket ingress endpoint and OCPP subprotocol validation are active.
- EV simulator is built and integrated in-repo.
- Core event flow implemented: Boot, Heartbeat, Transaction start/update/end, Firmware status.
- Reconnect with backoff implemented.
- Manual simulator REST controls implemented (`/api/ev/**`).
- Telemetry profiles and runtime profile switching implemented.
- Scenario orchestration implemented: run/status/stop.
- Optional TLS/mTLS wiring implemented for simulator `wss://` mode.
- Local dev cert artifacts generated for backend and simulator resource paths.

## Verified runtime outcomes

- Backend starts on `8443`.
- Simulator API runs on `8080` (or `8082` override).
- Simulator connects to backend and exchanges OCPP messages.
- Scenario `reconnectLoop` can be started and stopped via API.

## Remaining gaps

1. **Production certificate lifecycle**
   - Current certs are local self-signed dev artifacts only.
   - Production requires managed CA process, rotation, secret storage, and non-default passwords.
2. **Demo hardening (optional)**
   - Multi-EV concurrency and scripted rehearsal bundle can be improved further.

## Current defaults to remember

- Backend port: `8443`
- Simulator default app port: `8080`
- Common local override: simulator on `8082`
- Default non-TLS gateway URI in simulator config: `ws://localhost:8443/ocpp/{stationId}`

## Why certs were placeholders initially

- Safe default avoids committing private key material and fixed passwords.
- Team environments usually require each developer to generate local dev certs independently.
- This has now been addressed for your local workspace with generated dev certs and ignore rules.
