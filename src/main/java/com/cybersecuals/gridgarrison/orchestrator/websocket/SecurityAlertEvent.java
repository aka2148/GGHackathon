package com.cybersecuals.gridgarrison.orchestrator.websocket;

/** Published when a SecurityEventNotification is received. */
public record SecurityAlertEvent(String stationId, String rawPayload) {}