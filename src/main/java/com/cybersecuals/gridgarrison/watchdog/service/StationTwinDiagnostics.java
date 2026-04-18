package com.cybersecuals.gridgarrison.watchdog.service;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight diagnostics snapshot for dashboard and simulator integrations.
 */
public record StationTwinDiagnostics(
    String stationId,
    StationTwin.TwinStatus twinStatus,
    Instant lastHeartbeat,
    int totalAnomaliesRaised,
    int chargingAnomalyCount,
    int powerAnomalyCount,
    int temperatureAnomalyCount,
    int socAnomalyCount,
    int firmwareMismatchCount,
    boolean stationHashChanged,
    boolean powerSurgeDetected,
    String lastSeverity,
    List<String> lastAnomalyTypes,
    Instant lastAnomalyAt,
    Double lastPowerKw,
    Double lastTempC,
    Double lastSocPercent,
    String goldenHash
) {
}
