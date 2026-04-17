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
    private final Map<String, GoldenFlowState> goldenFlows = new ConcurrentHashMap<>();

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

        goldenFlows.compute(station, (id, state) -> {
            GoldenFlowState next = state == null ? GoldenFlowState.newState(id) : state;
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

        Map<String, GoldenFlowView> goldenFlowByStation = goldenFlows.values().stream()
            .sorted(Comparator.comparing(GoldenFlowState::lastUpdated,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(LinkedHashMap::new,
                (acc, state) -> acc.put(state.stationId(), state.toView()),
                LinkedHashMap::putAll);

        ChainHealth chainHealth = deriveChainHealth(latestTrustEvidence);

        return new RuntimeSnapshot(
            Instant.now(),
            eventCopy.size(),
            byType,
            byModule,
            stationViews,
            latestTrustByStation,
            chainHealth,
            goldenFlowByStation
        );
    }

    public synchronized void clear() {
        events.clear();
        stations.clear();
        goldenFlows.clear();
    }

    public synchronized void simulateFullFlow(String stationId) {
        simulateNormalScenario(stationId);
    }

    public synchronized void simulateNormalScenario(String stationId) {
        String id = (stationId == null || stationId.isBlank()) ? "CS-101" : stationId;

        appendEvent("trust", "ManufactureHashGeneratedEvent", id,
            "Manufacture pipeline generated golden hash 0xabc123", "INFO");
        appendEvent("trust", "GoldenHashSignedEvent", id,
            "Manufacturer ACME-MFG signed golden hash with private key", "INFO");
        appendEvent("trust", "SignedGoldenHashStoredOnChainEvent", id,
            "Signed golden hash stored on blockchain", "INFO");
        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("trust", "GoldenHashRequestedEvent", id,
            "Driver requested golden hash from blockchain", "INFO");
        appendEvent("trust", "CurrentStatusHashReportedEvent", id,
            "Station reported current status hash 0xabc123", "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "VERIFIED",
            "0xabc123",
            "0xabc123",
            "ACME-MFG",
            "yx557G/aOQVqqyE/KhYpqMXzhJtnkIPKDaWl2NGqiOSK0ciFyg1PPs325nijWoPJJQE77hpQ55bA0k4C96G68zpTCtHRIIFiW1DVzvtPuLF2CfsCUMZ6LA1vX3il7j8JURmW1Nj4QyOKRZdS0y+257VJ3+LRK9Iv4vifO56x+9WKU8233KWC20Y+aBxtq7Z4vGVjgigAVr7RY4W+CWxG9IY23GwJYz5QpoKM/kri1AaF5U49K4lbv6ZRmK+jELKbK3qHOuo7Q4nK1khs+ZVUiv0l03ptOZmwCRsoDAlWfcr5PwX8yhpgyoPeQ4+5AUF4OK46yRg9aqRn1Oz/cd18Aw==",
            true,
            "0x1111111111111111111111111111111111111111",
            null,
            "REACHABLE",
            Instant.now(),
            "Manufacturer signature verified and reported hash matches on-chain golden hash.",
            42L,
            "FAST"
        );
        appendEvent("trust", "GoldenSignatureVerifiedEvent", id,
            "Public key verification succeeded for on-chain golden hash signature", "INFO", evidence);
        appendEvent("trust", "GoldenHashVerifiedEvent", id,
            "Reported firmware hash matches on-chain golden hash", "INFO", evidence);
        appendEvent("watchdog", "TwinRegisteredEvent", id,
            "Digital twin registered for new station", "INFO");
        appendEvent("orchestrator", "TransactionEvent", id,
            "TransactionEvent START/UPDATE captured from OCPP stream", "INFO");
        appendEvent("watchdog", "SessionUpdatedEvent", id,
            "Session baseline updated for anomaly profiling", "INFO");
        appendEvent("watchdog", "AnomalySweepEvent", id,
            "Periodic sweep completed with no anomaly detected", "INFO");
        appendEvent("app", "ActionTakenEvent", id,
            "Operational state remains HEALTHY/MONITORING", "INFO");
    }

    public synchronized void simulateTamperScenario(String stationId) {
        String id = (stationId == null || stationId.isBlank()) ? "CS-TAMPER-01" : stationId;

        appendEvent("trust", "ManufactureHashGeneratedEvent", id,
            "Manufacture pipeline generated golden hash 0xabc123", "INFO");
        appendEvent("trust", "GoldenHashSignedEvent", id,
            "Manufacturer ACME-MFG signed golden hash with private key", "INFO");
        appendEvent("trust", "SignedGoldenHashStoredOnChainEvent", id,
            "Signed golden hash stored on blockchain", "INFO");
        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("trust", "GoldenHashRequestedEvent", id,
            "Driver requested golden hash from blockchain", "INFO");
        appendEvent("trust", "CurrentStatusHashReportedEvent", id,
            "Station reported current status hash 0xdeadbeef", "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "TAMPERED",
            "0xdeadbeef",
            "0xabc123",
            "ACME-MFG",
            "yx557G/aOQVqqyE/KhYpqMXzhJtnkIPKDaWl2NGqiOSK0ciFyg1PPs325nijWoPJJQE77hpQ55bA0k4C96G68zpTCtHRIIFiW1DVzvtPuLF2CfsCUMZ6LA1vX3il7j8JURmW1Nj4QyOKRZdS0y+257VJ3+LRK9Iv4vifO56x+9WKU8233KWC20Y+aBxtq7Z4vGVjgigAVr7RY4W+CWxG9IY23GwJYz5QpoKM/kri1AaF5U49K4lbv6ZRmK+jELKbK3qHOuo7Q4nK1khs+ZVUiv0l03ptOZmwCRsoDAlWfcr5PwX8yhpgyoPeQ4+5AUF4OK46yRg9aqRn1Oz/cd18Aw==",
            true,
            "0x1111111111111111111111111111111111111111",
            null,
            "REACHABLE",
            Instant.now(),
            "Reported hash differs from on-chain golden hash.",
            63L,
            "FAST"
        );
        appendEvent("trust", "GoldenSignatureVerifiedEvent", id,
            "Public key verification succeeded for on-chain golden hash signature", "INFO", evidence);
        appendEvent("trust", "GoldenHashTamperedEvent", id,
            "Live firmware hash does not match golden hash from Ganache contract", "WARN", evidence);
        appendEvent("watchdog", "TwinRegisteredEvent", id,
            "Digital twin registered for new station", "INFO");
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
                                                                ChainHealth chainHealth,
                                                                Map<String, GoldenFlowView> goldenFlowByStation) {
        }

        public record TrustEvidenceView(String stationId,
                                 String verdict,
                                 String reportedHash,
                                 String expectedHash,
                                                                 String manufacturerId,
                                                                 String manufacturerSignature,
                                                                 Boolean signatureVerified,
                                 String contractAddress,
                                 String txHash,
                                 String rpcStatus,
                                 Instant observedAt,
                                 String rationale,
                                 Long latencyMs,
                                 String latencyBand) {
        }

                public record GoldenFlowView(String stationId,
                                                                         String finalVerdict,
                                                                         Instant lastUpdated,
                                                                         List<GoldenFlowStepView> steps) {
                }

                public record GoldenFlowStepView(int step,
                                                                                 String title,
                                                                                 String status,
                                                                                 String detail) {
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

    private static final class GoldenFlowState {
        private static final String STATUS_PENDING = "PENDING";
        private static final String STATUS_DONE = "DONE";
        private static final String STATUS_FAILED = "FAILED";

        private static final String[] TITLES = new String[] {
            "Hash generated at manufacture",
            "Hash signed by manufacturer",
            "Signed golden hash stored on-chain",
            "Driver requested golden hash",
            "Station reported live status hash",
            "Public key verified signature",
            "Final hash match verdict"
        };

        private final String stationId;
        private final String[] statuses = new String[] {
            STATUS_PENDING,
            STATUS_PENDING,
            STATUS_PENDING,
            STATUS_PENDING,
            STATUS_PENDING,
            STATUS_PENDING,
            STATUS_PENDING
        };
        private final String[] details = new String[] {"-", "-", "-", "-", "-", "-", "-"};
        private String finalVerdict = "PENDING";
        private Instant lastUpdated;

        private GoldenFlowState(String stationId) {
            this.stationId = stationId;
        }

        static GoldenFlowState newState(String stationId) {
            return new GoldenFlowState(stationId);
        }

        void apply(TraceEvent event) {
            this.lastUpdated = event.occurredAt();
            String type = event.type();
            String detail = event.summary() == null || event.summary().isBlank() ? "-" : event.summary();

            switch (type) {
                case "ManufactureHashGeneratedEvent" -> markDone(0, detail);
                case "GoldenHashSignedEvent" -> markDone(1, detail);
                case "SignedGoldenHashStoredOnChainEvent" -> markDone(2, detail);
                case "GoldenHashRequestedEvent" -> markDone(3, detail);
                case "CurrentStatusHashReportedEvent" -> markDone(4, detail);
                case "GoldenSignatureVerifiedEvent" -> markDone(5, detail);
                case "GoldenSignatureVerificationFailedEvent" -> markFailed(5, detail);
                case "GoldenHashVerifiedEvent" -> {
                    markDone(6, detail);
                    finalVerdict = "VERIFIED";
                }
                case "GoldenHashTamperedEvent" -> {
                    markFailed(6, detail);
                    finalVerdict = "TAMPERED";
                }
                case "GoldenHashVerificationFailedEvent" -> {
                    markFailed(6, detail);
                    finalVerdict = "FAILED";
                }
                default -> {
                    // no-op
                }
            }
        }

        String stationId() {
            return stationId;
        }

        Instant lastUpdated() {
            return lastUpdated;
        }

        GoldenFlowView toView() {
            List<GoldenFlowStepView> steps = new ArrayList<>();
            for (int i = 0; i < TITLES.length; i++) {
                steps.add(new GoldenFlowStepView(i + 1, TITLES[i], statuses[i], details[i]));
            }
            return new GoldenFlowView(stationId, finalVerdict, lastUpdated, steps);
        }

        private void markDone(int index, String detail) {
            statuses[index] = STATUS_DONE;
            details[index] = detail;
        }

        private void markFailed(int index, String detail) {
            statuses[index] = STATUS_FAILED;
            details[index] = detail;
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
