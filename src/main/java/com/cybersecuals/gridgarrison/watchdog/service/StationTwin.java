package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a station's digital twin state.
 * Exposed as part of the {@code watchdog} module's public API.
 */
@Value
@Builder
public class StationTwin {

    String stationId;
    Instant registeredAt;
    Instant lastHeartbeat;

    /** All sessions observed since registration (bounded ring buffer in impl). */
    List<ChargingSession> recentSessions;

    /** Running average energy per session (kWh), used as baseline. */
    double avgEnergyPerSessionKwh;

    /** Number of anomalies raised lifetime. */
    int totalAnomaliesRaised;

    TwinStatus status;

    public enum TwinStatus {
        HEALTHY,
        SUSPICIOUS,
        QUARANTINED,
        OFFLINE
    }
}

/**
 * Describes a detected behavioural anomaly.
 * Exposed as part of the {@code watchdog} module's public API.
 */
@Value
@Builder
class AnomalyReport {

    String stationId;
    AnomalyType type;
    String description;
    double observedValue;
    double expectedValue;
    Instant detectedAt;

    public enum AnomalyType {
        ENERGY_SPIKE,          // single session >> baseline
        RAPID_RECONNECT,       // boot loops / crash cycling
        CLOCK_DRIFT,           // timestamp deviation from grid time
        FIRMWARE_MISMATCH,     // reported hash ≠ golden hash
        SESSION_OVERFLOW,      // concurrent sessions > EVSE capacity
        HEARTBEAT_MISSED       // station went silent
    }
}
