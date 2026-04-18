package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a station's digital twin state.
 */
@Value
@Builder
public class StationTwin {

    String  stationId;
    Instant registeredAt;
    Instant lastHeartbeat;

    List<ChargingSession> recentSessions;

    double avgEnergyPerSessionKwh;
    int    totalAnomaliesRaised;

    TwinStatus status;

    public enum TwinStatus {
        HEALTHY,
        SUSPICIOUS,
        QUARANTINED,
        OFFLINE
    }
}

/**
 * Describes one detected anomaly signal.
 * Package-private — external consumers receive AnomalyEvent.
 *
 * ADDED new AnomalyType values for the four real-time detectors:
 *   POWER_SPIKE_ABSOLUTE, POWER_SPIKE_RATE, POWER_TWIN_MISMATCH
 *   TEMPERATURE_SPIKE, TEMPERATURE_RATE
 *   SOC_SPOOF, SOC_RATE_MISMATCH
 */
@Value
@Builder
class AnomalyReport {

    String      stationId;
    AnomalyType type;
    String      description;
    double      observedValue;
    double      expectedValue;
    Instant     detectedAt;

    public enum AnomalyType {
        // Session-level (original rules)
        ENERGY_SPIKE,
        RAPID_RECONNECT,
        CLOCK_DRIFT,
        SESSION_OVERFLOW,
        HEARTBEAT_MISSED,

        // CAT-1 Power (real-time telemetry)
        POWER_SPIKE_ABSOLUTE,    // power > hard cap
        POWER_SPIKE_RATE,        // power jumped too fast between samples
        POWER_TWIN_MISMATCH,     // live power >> twin baseline

        // CAT-2 Temperature (real-time telemetry)
        TEMPERATURE_SPIKE,       // temp > hard cap
        TEMPERATURE_RATE,        // temp rising too fast

        // CAT-3 SoC spoof (real-time telemetry)
        SOC_SPOOF,               // SoC jump too large in one sample
        SOC_RATE_MISMATCH,       // SoC increasing faster than power allows

        // Ideal-vs-actual drift (user-session simulation)
        TWIN_DRIFT_ENERGY,       // cumulative energy deviates from ideal profile
        TWIN_DRIFT_POWER,        // instantaneous power deviates from ideal profile

        // CAT-4 Identity / firmware
        FIRMWARE_MISMATCH        // hash ≠ golden hash
    }
}