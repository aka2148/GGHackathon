package com.cybersecuals.gridgarrison.visualizer;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RuntimeTraceService {

    private static final int MAX_EVENTS = 200;
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final Deque<TraceEvent> events = new ArrayDeque<>(MAX_EVENTS);
    private final Map<String, StationState> stations = new ConcurrentHashMap<>();

    public synchronized void appendEvent(String module,
                                         String type,
                                         String stationId,
                                         String summary,
                                         String severity) {
        appendEvent(module, type, stationId, summary, severity, null);
    }

    public synchronized void appendEvent(String module,
                                         String type,
                                         String stationId,
                                         String summary,
                                         String severity,
                                         TrustEvidenceView trustEvidence) {
        String station = (stationId == null || stationId.isBlank()) ? "UNKNOWN" : stationId;
        TraceEvent event = new TraceEvent(
            Instant.now(),
            (module == null || module.isBlank()) ? "unknown" : module,
            type,
            station,
            summary == null ? "" : summary,
            (severity == null || severity.isBlank()) ? "INFO" : severity,
            trustEvidence
        );

        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(event);

        stations.compute(station, (id, state) -> {
            StationState next = state == null ? StationState.newState(id) : state;
            next.apply(event);
            return next;
        });
    }

    public synchronized List<TraceEvent> getRecentEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_EVENTS));
        List<TraceEvent> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparing(TraceEvent::occurredAt).reversed());
        if (copy.size() <= safeLimit) {
            return copy;
        }
        return new ArrayList<>(copy.subList(0, safeLimit));
    }

    public synchronized RuntimeSnapshot snapshot() {
        List<TraceEvent> eventCopy = new ArrayList<>(events);
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byModule = new LinkedHashMap<>();
        Map<String, TrustEvidenceView> latestTrustByStation = new LinkedHashMap<>();
        TrustEvidenceView latestTrustEvidence = null;

        for (TraceEvent e : eventCopy) {
            byType.merge(e.type(), 1L, Long::sum);
            byModule.merge(e.module(), 1L, Long::sum);

            if (e.trustEvidence() != null) {
                latestTrustByStation.put(e.stationId(), e.trustEvidence());
                latestTrustEvidence = e.trustEvidence();
            }
        }

        List<StationView> stationViews = stations.values().stream()
            .map(StationState::toView)
            .sorted(Comparator.comparing(StationView::lastSeen,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        ChainHealth chainHealth = deriveChainHealth(latestTrustEvidence);

        return new RuntimeSnapshot(
            Instant.now(),
            eventCopy.size(),
            byType,
            byModule,
            stationViews,
            latestTrustByStation,
            chainHealth
        );
    }

    public synchronized void clear() {
        events.clear();
        stations.clear();
    }

    public synchronized void simulateFullFlow(String stationId) {
        simulateNormalScenario(stationId);
    }

    public synchronized void simulateNormalScenario(String stationId) {
        String id = (stationId == null || stationId.isBlank()) ? "CS-101" : stationId;

        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("watchdog", "TwinRegisteredEvent", id,
            "Digital twin registered for new station", "INFO");
        appendEvent("orchestrator", "TransactionEvent", id,
            "TransactionEvent START/UPDATE captured from OCPP stream", "INFO");
        appendEvent("watchdog", "SessionUpdatedEvent", id,
            "Session baseline updated for anomaly profiling", "INFO");
        appendEvent("orchestrator", "FirmwareStatusEvent", id,
            "FirmwareStatusNotification dispatched to trust module", "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "VERIFIED",
            "0xabc123",
            "0xabc123",
            "0x1111111111111111111111111111111111111111",
            null,
            "REACHABLE",
            Instant.now(),
            "Reported hash matches on-chain golden hash.",
            42L,
            "FAST"
        );
        appendEvent("trust", "GoldenHashVerifiedEvent", id,
            "Reported firmware hash matches on-chain golden hash", "INFO", evidence);
        appendEvent("watchdog", "AnomalySweepEvent", id,
            "Periodic sweep completed with no anomaly detected", "INFO");
        appendEvent("app", "ActionTakenEvent", id,
            "Operational state remains HEALTHY/MONITORING", "INFO");
    }

    public synchronized void simulateTamperScenario(String stationId) {
        String id = (stationId == null || stationId.isBlank()) ? "CS-TAMPER-01" : stationId;

        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("watchdog", "TwinRegisteredEvent", id,
            "Digital twin registered for new station", "INFO");
        appendEvent("orchestrator", "FirmwareStatusEvent", id,
            "FirmwareStatusNotification dispatched to trust module", "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "TAMPERED",
            "0xdeadbeef",
            "0xabc123",
            "0x1111111111111111111111111111111111111111",
            null,
            "REACHABLE",
            Instant.now(),
            "Reported hash differs from on-chain golden hash.",
            63L,
            "FAST"
        );
        appendEvent("trust", "GoldenHashTamperedEvent", id,
            "Live firmware hash does not match golden hash from Ganache contract", "WARN", evidence);
        appendEvent("watchdog", "FirmwareMismatchAnomalyEvent", id,
            "Station marked suspicious due to firmware mismatch", "WARN");
        appendEvent("app", "ActionTakenEvent", id,
            "ALERT raised: station isolated for manual review", "WARN");
    }

    public synchronized void simulateAnomalyScenario(String stationId) {
        String id = (stationId == null || stationId.isBlank()) ? "CS-ANOM-01" : stationId;

        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("watchdog", "TwinRegisteredEvent", id,
            "Digital twin registered for new station", "INFO");
        appendEvent("orchestrator", "TransactionEvent", id,
            "TransactionEvent START/UPDATE captured from OCPP stream", "INFO");
        appendEvent("watchdog", "SessionUpdatedEvent", id,
            "Session baseline updated for anomaly profiling", "INFO");
        appendEvent("watchdog", "EnergySpikeAnomalyEvent", id,
            "Observed kWh exceeds learned baseline threshold", "WARN");
        appendEvent("app", "ActionTakenEvent", id,
            "SUSPICIOUS state applied and high-priority monitoring enabled", "INFO");
    }

    public record TraceEvent(Instant occurredAt,
                             String module,
                             String type,
                             String stationId,
                             String summary,
                            String severity,
                            TrustEvidenceView trustEvidence) {
    }

    public record RuntimeSnapshot(Instant capturedAt,
                                  int totalEvents,
                                  Map<String, Long> eventsByType,
                                  Map<String, Long> eventsByModule,
                                List<StationView> stations,
                                Map<String, TrustEvidenceView> latestTrustByStation,
                                ChainHealth chainHealth) {
        }

        public record TrustEvidenceView(String stationId,
                                 String verdict,
                                 String reportedHash,
                                 String expectedHash,
                                 String contractAddress,
                                 String txHash,
                                 String rpcStatus,
                                 Instant observedAt,
                                 String rationale,
                                 Long latencyMs,
                                 String latencyBand) {
        }

        public record ChainHealth(boolean rpcReachable,
                            boolean contractConfigured,
                            Instant lastCheckTime,
                            String lastLatencyBand,
                            String status) {
    }

    public record StationView(String stationId,
                              Instant lastSeen,
                              int bootCount,
                              int transactionCount,
                              int firmwareEventCount,
                              int securityAlertCount,
                              String lastEventType,
                              String operationalState) {
    }

    private static final class StationState {
        private final String stationId;
        private Instant lastSeen;
        private int bootCount;
        private int transactionCount;
        private int firmwareEventCount;
        private int securityAlertCount;
        private String lastEventType = "None";
        private String operationalState = "DISCOVERED";

        private StationState(String stationId) {
            this.stationId = stationId;
        }

        static StationState newState(String stationId) {
            return new StationState(stationId);
        }

        void apply(TraceEvent event) {
            this.lastSeen = event.occurredAt();
            this.lastEventType = event.type();

            String type = event.type();
            if (type.contains("Boot")) {
                bootCount++;
                operationalState = "ONLINE";
            }
            if (type.contains("Transaction") || type.contains("Session")) {
                transactionCount++;
            }
            if (type.contains("Firmware") || type.contains("Hash")) {
                firmwareEventCount++;
            }
            if (type.contains("Security") || "WARN".equalsIgnoreCase(event.severity())) {
                securityAlertCount++;
                operationalState = "ALERT";
            }
            if (type.contains("Anomaly") && "WARN".equalsIgnoreCase(event.severity())) {
                operationalState = "SUSPICIOUS";
            }
            if (type.contains("Tampered") || type.contains("Mismatch")) {
                operationalState = "ALERT";
            }
            if (type.contains("Anomaly") && securityAlertCount == 0) {
                operationalState = "MONITORING";
            }
        }

        StationView toView() {
            return new StationView(
                stationId,
                lastSeen,
                bootCount,
                transactionCount,
                firmwareEventCount,
                securityAlertCount,
                lastEventType,
                operationalState
            );
        }
    }

    private ChainHealth deriveChainHealth(TrustEvidenceView latestTrustEvidence) {
        if (latestTrustEvidence == null) {
            return new ChainHealth(false, false, null, "UNKNOWN", "NO_TRUST_DATA");
        }

        boolean reachable = "REACHABLE".equalsIgnoreCase(latestTrustEvidence.rpcStatus());
        String contractAddress = latestTrustEvidence.contractAddress();
        boolean configured = contractAddress != null
            && !contractAddress.isBlank()
            && !ZERO_ADDRESS.equalsIgnoreCase(contractAddress);

        String status = reachable ? "HEALTHY" : "DEGRADED";
        if (!configured) {
            status = "CONTRACT_MISSING";
        }

        return new ChainHealth(
            reachable,
            configured,
            latestTrustEvidence.observedAt(),
            latestTrustEvidence.latencyBand() == null ? "UNKNOWN" : latestTrustEvidence.latencyBand(),
            status
        );
    }
}
