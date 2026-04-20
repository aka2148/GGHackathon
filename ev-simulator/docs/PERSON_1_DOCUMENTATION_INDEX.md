# PERSON 1 DOCUMENTATION INDEX

## Read order

1. `PERSON_1_WORK_SUMMARY.md`
   - scope, delivered work, and outstanding items
2. `VERIFY_SETUP.md`
   - repeatable verification checklist and smoke tests
3. `PERSON_1_CHECKLIST.md`
   - quick command set and ownership checklist

## Related docs in workspace

1. `ev-simulator/simulator-app/README.md`
   - simulator capabilities, profiles, APIs, dashboard behavior
2. `README.md` (repo root)
   - full-stack startup sequence and environment assumptions
3. `HACKATHON_SYSTEM_GUIDE.md`
   - judge/demo narrative and fallback runbook

## Helper startup guides

1. `STARTUP_GUIDE.bat` (Windows)
2. `STARTUP_GUIDE.sh` (Linux/macOS)

## Canonical runtime defaults

1. Backend URL: `https://localhost:8443`
2. Simulator URL: `http://localhost:8082`
3. Simulator dashboard: `http://localhost:8082/ev-dashboard.html`
4. `demo-mtls` station ID: `EV-Simulator-001`
5. Non-TLS gateway: `ws://localhost:8443/ocpp/{stationId}`
6. TLS gateway: `wss://localhost:8443/ocpp/{stationId}`
