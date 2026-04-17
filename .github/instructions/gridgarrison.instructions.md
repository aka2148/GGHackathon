# GridGarrison Instructions

## Scope
Apply these instructions to the GridGarrison backend workspace unless a more specific file-level instruction says otherwise.

## Project Goal
Build a workable hackathon demo backend for an EV charging platform that:
- simulates charging sessions for a dummy EV client,
- ingests OCPP-style station traffic over WebSocket,
- verifies firmware trust using blockchain concepts,
- detects anomalous or malicious hardware behavior with a digital twin,
- exposes deterministic demo flows for the visualizer and judge walkthrough.

## Core Rules
- Keep the architecture event-driven. Modules communicate through Spring application events, not direct service calls across module boundaries.
- Preserve Spring Modulith boundaries. Do not introduce cross-module dependencies unless they are already part of the shared DTO layer or a documented public API.
- Keep implementation classes package-private where the codebase already follows that pattern. Public types should be reserved for module APIs, DTOs, and externally consumed contracts.
- Treat `shared` as a minimal cross-cutting DTO module only. Do not move module-owned events or internal logic into `shared`.
- Prefer deterministic demo behavior over realistic randomness when the visualizer or hackathon flow depends on reproducible outcomes.
- Keep the backend runnable without external infrastructure when possible. Use in-memory or local-dev defaults for demo mode unless the user explicitly asks for production hardening.
- Maintain the trust flow as a separate concern from anomaly detection. Firmware verification belongs in `trust`; twin state and anomaly rules belong in `watchdog`.
- Keep the visualizer focused on traceability and storytelling. Expose clean endpoints and event snapshots that make the demo easy to explain.
- Use concise, module-prefixed log messages so runtime traces are easy to follow during the demo.
- Externalize configuration in `application.yml` and environment variables. Avoid hard-coded URLs, secrets, addresses, and thresholds.
- When adding or changing events, keep payloads stable and minimal. Update listeners and tests together so the event contract stays coherent.
- Favor small, testable changes that support the demo path: connect, simulate charge, verify trust, detect anomalies, and show response state.

## Implementation Preferences
- Reuse the existing module layout: `orchestrator`, `trust`, `watchdog`, `visualizer`, and `shared`.
- Keep hackathon scenarios reproducible and easy to reset.
- Add or update tests when changing module boundaries, event contracts, trust logic, or anomaly detection behavior.
- Avoid introducing new frameworks or architectural layers unless they clearly reduce complexity for the demo.

## What to Optimize For
- A dummy EV client can connect reliably.
- The system can simulate a charge session from start to finish.
- Trust verification can flag verified vs tampered firmware.
- The digital twin can detect suspicious hardware or charging patterns.
- The visualizer can explain what happened with minimal operator effort.
