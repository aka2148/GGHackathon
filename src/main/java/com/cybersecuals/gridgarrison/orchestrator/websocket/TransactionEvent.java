package com.cybersecuals.gridgarrison.orchestrator.websocket;

/** Published when a TransactionEvent (start/update/end) is received. */
public record TransactionEvent(String stationId, String rawPayload) {}