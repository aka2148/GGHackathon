package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;

import java.util.Optional;

/**
 * Public API of the {@code watchdog} module.
 *
 * Maintains an in-memory (and optionally persisted) Digital Twin for every
 * known charging station. Behavioural deviations from the learned baseline
 * are flagged as anomalies and can trigger alerts or station quarantine.
 */
public interface DigitalTwinService {

    /**
     * Register a new station twin on first BootNotification.
     *
     * @param stationId station identity string
     */
    void registerStation(String stationId);

    /**
     * Update the twin state with a new charging session event.
     * Called on each TransactionEvent (START / UPDATE / END).
     *
     * @param session the current session snapshot
     */
    void updateSessionState(ChargingSession session);

    /**
     * Analyse recent telemetry and flag anomalies.
     * Intended to be called periodically (e.g. every 60 s via @Scheduled).
     *
     * @param stationId station to analyse
     * @return an {@link AnomalyReport} if an anomaly was detected, empty otherwise
     */
    Optional<AnomalyReport> detectAnomalies(String stationId);

    /**
     * Fetch the current twin snapshot for a station.
     *
     * @param stationId station identity
     * @return current twin state, or empty if station is not registered
     */
    Optional<StationTwin> getTwin(String stationId);
}
