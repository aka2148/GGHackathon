package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Digital Twin implementation.
 * Package-private — module consumers use {@link DigitalTwinService}.
 *
 * Subscribes to orchestrator events via {@link ApplicationModuleListener}
 * without creating a direct compile-time dependency on the orchestrator module.
 */
@Slf4j
@Service
class DigitalTwinServiceImpl implements DigitalTwinService {

    /** Max sessions retained per twin before oldest are evicted. */
    private static final int SESSION_RING_SIZE = 50;

    /** Energy spike threshold — sessions > (baseline * factor) are flagged. */
    private static final double ENERGY_SPIKE_FACTOR = 3.0;
    private static final int RAPID_RECONNECT_THRESHOLD = 3;
    private static final Duration RAPID_RECONNECT_WINDOW = Duration.ofMinutes(10);
    private static final Duration SHORT_SESSION_DURATION = Duration.ofMinutes(2);

    @Value("${gridgarrison.watchdog.heartbeat-timeout-ms:180000}")
    private long heartbeatTimeoutMs;

    // stationId → twin state
    private final Map<String, MutableTwin> twins = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public void registerStation(String stationId) {
        twins.computeIfAbsent(stationId, id -> {
            log.info("[Watchdog] Registering digital twin for stationId={}", id);
            return new MutableTwin(id, Instant.now());
        });
    }

    @Override
    public void updateSessionState(ChargingSession session) {
        MutableTwin twin = twins.get(session.getStationId());
        if (twin == null) {
            registerStation(session.getStationId());
            twin = twins.get(session.getStationId());
        }
        twin.addSession(session);
        log.debug("[Watchdog] Twin updated — stationId={} state={}", session.getStationId(), session.getState());
    }

    @Override
    public Optional<AnomalyReport> detectAnomalies(String stationId) {
        MutableTwin twin = twins.get(stationId);
        if (twin == null) return Optional.empty();

        // Rule 1: Energy spike in most recent session
        List<ChargingSession> sessions = twin.getSessions();
        if (!sessions.isEmpty()) {
            ChargingSession latest = sessions.get(sessions.size() - 1);
            double baseline = twin.getAvgEnergy();
            if (baseline > 0 && latest.getEnergyDeliveredKwh() > baseline * ENERGY_SPIKE_FACTOR) {
                AnomalyReport report = AnomalyReport.builder()
                    .stationId(stationId)
                    .type(AnomalyReport.AnomalyType.ENERGY_SPIKE)
                    .description("Session energy far exceeds station baseline")
                    .observedValue(latest.getEnergyDeliveredKwh())
                    .expectedValue(baseline)
                    .detectedAt(Instant.now())
                    .build();
                twin.incrementAnomalyCount();
                log.warn("[Watchdog] ANOMALY detected — stationId={} type=ENERGY_SPIKE", stationId);
                return Optional.of(report);
            }
        }

        // Rule 2: frequent short sessions in a short window (demo-grade reconnect heuristic)
        long rapidReconnectCount = sessions.stream()
            .filter(s -> s.getEndedAt() != null && s.getStartedAt() != null)
            .filter(s -> s.getEndedAt().isAfter(Instant.now().minus(RAPID_RECONNECT_WINDOW)))
            .filter(s -> Duration.between(s.getStartedAt(), s.getEndedAt()).compareTo(SHORT_SESSION_DURATION) <= 0)
            .count();
        if (rapidReconnectCount >= RAPID_RECONNECT_THRESHOLD) {
            AnomalyReport report = AnomalyReport.builder()
                .stationId(stationId)
                .type(AnomalyReport.AnomalyType.RAPID_RECONNECT)
                .description("Multiple short sessions observed in a short window")
                .observedValue(rapidReconnectCount)
                .expectedValue(RAPID_RECONNECT_THRESHOLD - 1)
                .detectedAt(Instant.now())
                .build();
            twin.incrementAnomalyCount();
            log.warn("[Watchdog] ANOMALY detected — stationId={} type=RAPID_RECONNECT", stationId);
            return Optional.of(report);
        }

        // Rule 3: heartbeat timeout
        long silenceMs = Duration.between(twin.lastHeartbeat, Instant.now()).toMillis();
        if (silenceMs > heartbeatTimeoutMs) {
            AnomalyReport report = AnomalyReport.builder()
                .stationId(stationId)
                .type(AnomalyReport.AnomalyType.HEARTBEAT_MISSED)
                .description("Station heartbeat/session updates have timed out")
                .observedValue(silenceMs)
                .expectedValue(heartbeatTimeoutMs)
                .detectedAt(Instant.now())
                .build();
            twin.incrementAnomalyCount();
            log.warn("[Watchdog] ANOMALY detected — stationId={} type=HEARTBEAT_MISSED", stationId);
            return Optional.of(report);
        }

        return Optional.empty();
    }

    @Override
    public Optional<StationTwin> getTwin(String stationId) {
        MutableTwin t = twins.get(stationId);
        return Optional.ofNullable(t).map(MutableTwin::toSnapshot);
    }

    // -------------------------------------------------------------------------
    // Scheduled anomaly sweep (all registered stations every 60 s)
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${gridgarrison.watchdog.sweep-interval-ms:60000}")
    void scheduledAnomalySweep() {
        log.debug("[Watchdog] Anomaly sweep — stations={}", twins.size());
        twins.keySet().forEach(id -> detectAnomalies(id).ifPresent(r ->
            log.warn("[Watchdog] Sweep anomaly — stationId={} type={}", r.getStationId(), r.getType())
        ));
    }

    // -------------------------------------------------------------------------
    // Internal mutable state (package-private record)
    // -------------------------------------------------------------------------

    private static class MutableTwin {
        final String    stationId;
        final Instant   registeredAt;
        Instant         lastHeartbeat;
        final Deque<ChargingSession> sessions = new ArrayDeque<>(SESSION_RING_SIZE);
        int anomalyCount = 0;

        MutableTwin(String stationId, Instant registeredAt) {
            this.stationId    = stationId;
            this.registeredAt = registeredAt;
            this.lastHeartbeat = registeredAt;
        }

        void addSession(ChargingSession s) {
            if (sessions.size() >= SESSION_RING_SIZE) sessions.pollFirst();
            sessions.addLast(s);
            lastHeartbeat = Instant.now();
        }

        List<ChargingSession> getSessions() { return new ArrayList<>(sessions); }

        double getAvgEnergy() {
            return sessions.stream()
                .mapToDouble(ChargingSession::getEnergyDeliveredKwh)
                .average()
                .orElse(0.0);
        }

        void incrementAnomalyCount() { anomalyCount++; }

        StationTwin toSnapshot() {
            return StationTwin.builder()
                .stationId(stationId)
                .registeredAt(registeredAt)
                .lastHeartbeat(lastHeartbeat)
                .recentSessions(getSessions())
                .avgEnergyPerSessionKwh(getAvgEnergy())
                .totalAnomaliesRaised(anomalyCount)
                .status(anomalyCount > 5
                    ? StationTwin.TwinStatus.SUSPICIOUS
                    : StationTwin.TwinStatus.HEALTHY)
                .build();
        }
    }
}
