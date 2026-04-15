package com.cybersecuals.gridgarrison.orchestrator.websocket;

/** Published when a station sends BootNotification. */
public record StationBootEvent(String stationId, String rawPayload) {}