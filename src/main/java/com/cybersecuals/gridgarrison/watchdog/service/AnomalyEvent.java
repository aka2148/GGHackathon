package com.cybersecuals.gridgarrison.watchdog.service;

/**
 * Spring application event published by DigitalTwinServiceImpl
 * whenever a confirmed anomaly is detected.
 *
 * Consumed by EscrowAnomalyListener (trust module) to trigger refund.
 *
 * FIXED vs original:
 *   - Added sessionId field so EscrowAnomalyListener can log which session was affected.
 *   - Kept the same four Severity levels the listener already switches on.
 */
public record AnomalyEvent(
    String stationId,
    String sessionId,                         // which session triggered this
    AnomalyReport.AnomalyType primaryType,
    String description,
    double observedValue,
    double expectedValue,
    Severity severity
) {
    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}