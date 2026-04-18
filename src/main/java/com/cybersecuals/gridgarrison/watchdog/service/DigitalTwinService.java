package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;
import com.cybersecuals.gridgarrison.shared.dto.TelemetrySample;

import java.util.List;
import java.util.Optional;

/**
 * Public API of the watchdog module.
 *
 * Added vs original:
 *   - ingestTelemetry()  — real-time per-sample detection (power, temp, SoC, firmware)
 *   - clearQuarantine()  — admin endpoint to reset a quarantined station
 */
public interface DigitalTwinService {

    void registerStation(String stationId);

    void updateSessionState(ChargingSession session);

    /**
     * Ingest one MeterValues telemetry sample.
     * Runs all four real-time detectors and publishes AnomalyEvent if anything fires.
     * Returns empty list if the sample is clean.
     */
    List<AnomalyReport> ingestTelemetry(TelemetrySample sample);

    /**
     * Ingest telemetry with an optional ideal simulation baseline.
     * The expectation fields are used for drift detection so the twin can
     * compare real charging behavior to the intended session profile.
     */
    default List<AnomalyReport> ingestTelemetry(TelemetrySample sample,
                                                TelemetryExpectation expectation) {
        return ingestTelemetry(sample);
    }

    Optional<AnomalyReport> detectAnomalies(String stationId);

    Optional<StationTwin> getTwin(String stationId);

    Optional<StationTwinDiagnostics> getDiagnostics(String stationId);

    void setGoldenHash(String stationId, String goldenHash);

    void quarantineStation(String stationId, String reason);

    void clearQuarantine(String stationId);

    record TelemetryExpectation(
        Double expectedEnergyDeliveredKwh,
        Double expectedPowerKw,
        Double expectedSocPercent,
        Double elapsedRatio,
        Double driftThresholdPct
    ) {
    }
}