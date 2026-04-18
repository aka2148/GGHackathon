# PERSON 1 DOCUMENTATION INDEX (Current)

## Read first

1. `PERSON_1_WORK_SUMMARY.md` — what is completed and what remains
2. `VERIFY_SETUP.md` — current build/run/test checklist
3. `PERSON_1_CHECKLIST.md` — action checklist and mTLS run commands

## Current status snapshot

### Completed
- EV simulator in-repo under `ev-simulator/simulator-app`
- Boot/heartbeat/transaction/firmware OCPP events
- Reconnect backoff
- Manual control APIs
- Telemetry profiles
- Scenario run/status/stop
- Local dev cert generation completed

### Remaining
- Production-grade certificate lifecycle and secret management

## Helper scripts

- `STARTUP_GUIDE.bat` (Windows)
- `STARTUP_GUIDE.sh` (Mac/Linux)

## Canonical runtime defaults

- Backend: `8443`
- Simulator app: `8080` (use `8082` when needed)
- Simulator gateway (non-TLS): `ws://localhost:8443/ocpp/{stationId}`
