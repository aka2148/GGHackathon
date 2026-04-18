package com.cybersecuals.gridgarrison.shared.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * One real-time telemetry sample from a charging station (OCPP MeterValues).
 * Primary input to DigitalTwinServiceImpl's four real-time anomaly detectors.
 * All sensor fields are nullable — not every station model reports everything.
 */
@Value
@Builder
public class TelemetrySample {

    String  stationId;
    String  sessionId;
    Instant sampledAt;

    // ── Power / Energy ────────────────────────────────────────────────────────

    /** Instantaneous power in kW. Core power-spike detection signal. */
    Double powerKw;

    /** Cumulative energy delivered so far this session (kWh). */
    Double energyDeliveredKwh;

    Double currentA;
    Double voltageV;

    // ── Thermal ───────────────────────────────────────────────────────────────

    /** Connector temperature °C. Used for temperature spike detection. */
    Double connectorTempC;

    /** Battery temperature if vehicle shares it. Null if not available. */
    Double batteryTempC;

    // ── State of Charge ───────────────────────────────────────────────────────

    /** State of Charge 0–100 %. Used for spoof detection. */
    Double socPercent;

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Firmware hash re-announced mid-session (some stations do this). */
    String reportedFirmwareHash;

    String connectorId;
}