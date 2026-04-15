package com.cybersecuals.gridgarrison.orchestrator.websocket;

/** Published when a station reports a firmware status change. */
public record FirmwareStatusEvent(String stationId, String rawPayload) {}