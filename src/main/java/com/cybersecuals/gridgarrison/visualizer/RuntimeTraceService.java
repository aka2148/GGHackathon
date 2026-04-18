package com.cybersecuals.gridgarrison.visualizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class RuntimeTraceService {

    private static final int MAX_EVENTS = 200;
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<ComponentTemplate> DEFAULT_COMPONENTS = List.of(
        new ComponentTemplate("power-supply", "Power Supply Unit", "AC input, DC rails and surge tolerance"),
        new ComponentTemplate("rectifier-stage", "Rectifier Stage", "AC to DC conversion path integrity"),
        new ComponentTemplate("power-electronics", "Power Electronics", "Inverter and converter control path"),
        new ComponentTemplate("cooling-system", "Cooling System", "Thermal management and fan loop health"),
        new ComponentTemplate("ocpp-comms-module", "OCPP Communication Module", "Backend communication and control channel"),
        new ComponentTemplate("energy-meter", "Energy Meter", "kWh metering and billing trust source"),
        new ComponentTemplate("connector-relay", "Connector Relay", "Contactor and isolation switching path")
    );

    private final Deque<TraceEvent> events = new ArrayDeque<>(MAX_EVENTS);
    private final Map<String, StationState> stations = new ConcurrentHashMap<>();
    private final Map<String, GoldenFlowState> goldenFlows = new ConcurrentHashMap<>();
    private final Map<String, StationComponentState> componentStates = new ConcurrentHashMap<>();
    private final Map<String, GeneratedHashView> generatedHashes = new ConcurrentHashMap<>();
    private final Map<String, GeneratedVerificationView> generatedComparisons = new ConcurrentHashMap<>();
    private final Map<String, OnChainBaselineView> onChainBaselines = new ConcurrentHashMap<>();

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

        if (trustEvidence != null
            && trustEvidence.expectedHash() != null
            && !trustEvidence.expectedHash().isBlank()) {
            setOnChainBaselineHashInternal(station, trustEvidence.expectedHash(), "TRUST_EVENT");
        }

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

        Set<String> knownStations = new LinkedHashSet<>(stations.keySet());
        knownStations.addAll(componentStates.keySet());

        Map<String, StationComponentPanelView> componentPanelsByStation = new LinkedHashMap<>();
        for (String stationId : knownStations) {
            StationComponentState state = ensureComponentState(stationId);
            componentPanelsByStation.put(stationId, state.toView());
        }

        Map<String, GeneratedHashView> generatedHashByStation = generatedHashes.values().stream()
            .sorted(Comparator.comparing(GeneratedHashView::generatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(LinkedHashMap::new,
                (acc, view) -> acc.put(view.stationId(), view),
                LinkedHashMap::putAll);

        Map<String, GeneratedVerificationView> generatedVerificationByStation = generatedComparisons.values().stream()
            .sorted(Comparator.comparing(GeneratedVerificationView::observedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(LinkedHashMap::new,
                (acc, view) -> acc.put(view.stationId(), view),
                LinkedHashMap::putAll);

        Map<String, OnChainBaselineView> onChainBaselineByStation = onChainBaselines.values().stream()
            .sorted(Comparator.comparing(OnChainBaselineView::observedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(LinkedHashMap::new,
                (acc, view) -> acc.put(view.stationId(), view),
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
            goldenFlowByStation,
            componentPanelsByStation,
            generatedHashByStation,
            generatedVerificationByStation,
            onChainBaselineByStation
        );
    }

    public synchronized void clear() {
        events.clear();
        stations.clear();
        goldenFlows.clear();
        componentStates.clear();
        generatedHashes.clear();
        generatedComparisons.clear();
        onChainBaselines.clear();
    }

    public synchronized void simulateFullFlow(String stationId) {
        simulateNormalScenario(stationId);
    }

    public synchronized void simulateNormalScenario(String stationId) {
        String id = normalizeStationId(stationId, "CS-101");
        setAllComponentTamper(id, false);
        GeneratedHashView generatedHash = generateCurrentHashInternal(id, true);
        String expectedHash = resolveHealthyBaselineHash(id);

        appendEvent("trust", "ManufactureHashGeneratedEvent", id,
            "Manufacture pipeline generated golden hash " + expectedHash, "INFO");
        appendEvent("trust", "GoldenHashSignedEvent", id,
            "Manufacturer ACME-MFG signed golden hash with private key", "INFO");
        appendEvent("trust", "SignedGoldenHashStoredOnChainEvent", id,
            "Signed golden hash stored on blockchain", "INFO");
        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("trust", "GoldenHashRequestedEvent", id,
            "Driver requested golden hash from blockchain", "INFO");
        appendEvent("trust", "CurrentStatusHashReportedEvent", id,
            "Station reported current status hash " + generatedHash.hash(), "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "VERIFIED",
            generatedHash.hash(),
            expectedHash,
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
        String id = normalizeStationId(stationId, "CS-TAMPER-01");
        setAllComponentTamper(id, false);
        setComponentTamper(id, "power-supply", true);
        GeneratedHashView generatedHash = generateCurrentHashInternal(id, true);
        String expectedHash = resolveHealthyBaselineHash(id);

        appendEvent("trust", "ManufactureHashGeneratedEvent", id,
            "Manufacture pipeline generated golden hash " + expectedHash, "INFO");
        appendEvent("trust", "GoldenHashSignedEvent", id,
            "Manufacturer ACME-MFG signed golden hash with private key", "INFO");
        appendEvent("trust", "SignedGoldenHashStoredOnChainEvent", id,
            "Signed golden hash stored on blockchain", "INFO");
        appendEvent("orchestrator", "StationBootEvent", id,
            "BootNotification received and Accepted response returned", "INFO");
        appendEvent("trust", "GoldenHashRequestedEvent", id,
            "Driver requested golden hash from blockchain", "INFO");
        appendEvent("trust", "CurrentStatusHashReportedEvent", id,
            "Station reported current status hash " + generatedHash.hash(), "INFO");
        TrustEvidenceView evidence = new TrustEvidenceView(
            id,
            "TAMPERED",
            generatedHash.hash(),
            expectedHash,
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
        String id = normalizeStationId(stationId, "CS-ANOM-01");

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

    public synchronized StationComponentPanelView getStationComponents(String stationId) {
        String id = normalizeStationId(stationId, "CS-101");
        return ensureComponentState(id).toView();
    }

    public synchronized StationComponentPanelView updateComponentTamper(String stationId,
                                                                        String componentId,
                                                                        boolean tampered) {
        String id = normalizeStationId(stationId, "CS-101");
        setComponentTamper(id, componentId, tampered);
        generatedHashes.remove(id);
        generatedComparisons.remove(id);

        StationComponentState state = ensureComponentState(id);
        appendEvent("watchdog", "ComponentConditionUpdatedEvent", id,
            "Component " + componentId + " marked " + (tampered ? "TAMPERED" : "HEALTHY"),
            tampered ? "WARN" : "INFO");
        return state.toView();
    }

    public synchronized GeneratedHashView generateCurrentHashForStation(String stationId) {
        String id = normalizeStationId(stationId, "CS-101");
        return generateCurrentHashInternal(id, true);
    }

    public synchronized GeneratedHashView generateCurrentHashForStation(String stationId,
                                                                        String onChainExpectedHash,
                                                                        String baselineSource) {
        String id = normalizeStationId(stationId, "CS-101");
        if (onChainExpectedHash != null && !onChainExpectedHash.isBlank()) {
            setOnChainBaselineHashInternal(id, onChainExpectedHash,
                (baselineSource == null || baselineSource.isBlank()) ? "LIVE_CHAIN_READ" : baselineSource);
        }
        return generateCurrentHashInternal(id, true);
    }

    public synchronized GeneratedHashView generateHealthyHashForStation(String stationId) {
        String id = normalizeStationId(stationId, "CS-101");
        StationComponentState state = ensureComponentState(id);
        if (state.anyTampered()) {
            throw new IllegalStateException(
                "Cannot create signed baseline while modules are abnormal. Restore all modules to NORMAL first."
            );
        }
        return generateCurrentHashInternal(id, true);
    }

    public synchronized OnChainBaselineView setOnChainBaselineHash(String stationId,
                                                                   String onChainExpectedHash,
                                                                   String source) {
        String id = normalizeStationId(stationId, "CS-101");
        return setOnChainBaselineHashInternal(id, onChainExpectedHash,
            (source == null || source.isBlank()) ? "MANUAL_BASELINE_SET" : source);
    }

    public synchronized String resolveReportedHashForStation(String stationId) {
        String id = normalizeStationId(stationId, "CS-101");
        GeneratedHashView existing = generatedHashes.get(id);
        if (existing != null) {
            return existing.hash();
        }
        return generateCurrentHashInternal(id, false).hash();
    }

    public synchronized GeneratedVerificationView verifyAgainstGeneratedHash(String stationId,
                                                                             String reportedHash) {
        String id = normalizeStationId(stationId, "CS-101");
        GeneratedHashView generated = generatedHashes.get(id);
        if (generated == null) {
            generated = generateCurrentHashInternal(id, false);
        }

        String effectiveReportedHash = (reportedHash == null || reportedHash.isBlank())
            ? generated.hash()
            : reportedHash;
        boolean matches = normaliseHash(effectiveReportedHash)
            .equalsIgnoreCase(normaliseHash(generated.hash()));

        GeneratedVerificationView comparison = new GeneratedVerificationView(
            id,
            VerificationMode.GENERATED_BASELINE.name(),
            effectiveReportedHash,
            generated.hash(),
            matches ? "VERIFIED" : "MISMATCH",
            matches
                ? "Reported hash matches generated component baseline."
                : "Reported hash differs from generated component baseline.",
            Instant.now()
        );
        generatedComparisons.put(id, comparison);

        String eventType = matches
            ? "GeneratedBaselineHashVerifiedEvent"
            : "GeneratedBaselineHashMismatchEvent";
        appendEvent("trust", eventType, id,
            comparison.rationale() + " expected=" + generated.hash() + " reported=" + effectiveReportedHash,
            matches ? "INFO" : "WARN");

        return comparison;
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
                                                                    Map<String, GoldenFlowView> goldenFlowByStation,
                                                                    Map<String, StationComponentPanelView> componentPanelsByStation,
                                                                    Map<String, GeneratedHashView> generatedHashByStation,
                                                                    Map<String, GeneratedVerificationView> generatedVerificationByStation,
                                                                    Map<String, OnChainBaselineView> onChainBaselineByStation) {
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

        public record StationComponentPanelView(String stationId,
                                                                                        boolean anyTampered,
                                                                                        Instant updatedAt,
                                                                                        List<ComponentConditionView> components) {
        }

        public record ComponentConditionView(String componentId,
                                                                                 String name,
                                                                                 String description,
                                                                                 boolean tampered) {
        }

        public record GeneratedHashView(String stationId,
                                                                        String hash,
                                                                        String generationSource,
                                                                        boolean anyTampered,
                                                                        List<String> tamperedComponents,
                                                                        Instant generatedAt) {
        }

        public record GeneratedVerificationView(String stationId,
                                                                                        String mode,
                                                                                        String reportedHash,
                                                                                        String baselineHash,
                                                                                        String verdict,
                                                                                        String rationale,
                                                                                        Instant observedAt) {
        }

        public record OnChainBaselineView(String stationId,
                          String expectedHash,
                          String source,
                          Instant observedAt) {
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

        public enum VerificationMode {
                BLOCKCHAIN_BASELINE,
                GENERATED_BASELINE
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
                              String operationalState,
                              String lastAuthorizationMode) {
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
        private String lastAuthorizationMode = "UNKNOWN";

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
                String authorizationMode = resolveAuthorizationMode(event.summary());
                if (authorizationMode != null && !authorizationMode.isBlank()) {
                    lastAuthorizationMode = authorizationMode;
                }
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
                operationalState,
                lastAuthorizationMode
            );
        }

        private String resolveAuthorizationMode(String summary) {
            if (summary == null || summary.isBlank() || !summary.contains("authorizationMode")) {
                return null;
            }
            try {
                JsonNode root = JSON.readTree(summary);
                JsonNode value = root.get("authorizationMode");
                if (value != null && !value.isNull()) {
                    String text = value.asText();
                    return text == null || text.isBlank() ? null : text;
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }
    }

    private static final class StationComponentState {
        private final String stationId;
        private final Map<String, ComponentRuntimeState> components = new LinkedHashMap<>();
        private Instant updatedAt;

        private StationComponentState(String stationId) {
            this.stationId = stationId;
            for (ComponentTemplate template : DEFAULT_COMPONENTS) {
                components.put(template.id(), new ComponentRuntimeState(
                    template.id(),
                    template.name(),
                    template.description(),
                    false
                ));
            }
            this.updatedAt = Instant.now();
        }

        static StationComponentState newState(String stationId) {
            return new StationComponentState(stationId);
        }

        void setTampered(String componentId, boolean tampered) {
            ComponentRuntimeState state = components.get(componentId);
            if (state == null) {
                throw new IllegalArgumentException("Unknown componentId: " + componentId);
            }
            state.tampered = tampered;
            updatedAt = Instant.now();
        }

        void setAllTampered(boolean tampered) {
            for (ComponentRuntimeState state : components.values()) {
                state.tampered = tampered;
            }
            updatedAt = Instant.now();
        }

        boolean anyTampered() {
            return components.values().stream().anyMatch(c -> c.tampered);
        }

        List<String> tamperedComponents() {
            return components.values().stream()
                .filter(c -> c.tampered)
                .map(c -> c.componentId)
                .toList();
        }

        String canonicalStatePayload() {
            StringBuilder builder = new StringBuilder();
            builder.append("v1|");
            for (ComponentRuntimeState component : components.values()) {
                builder.append(component.componentId)
                    .append('=')
                    .append(component.tampered ? '1' : '0')
                    .append(';');
            }
            return builder.toString();
        }

        StationComponentPanelView toView() {
            List<ComponentConditionView> views = components.values().stream()
                .map(component -> new ComponentConditionView(
                    component.componentId,
                    component.name,
                    component.description,
                    component.tampered
                ))
                .toList();
            return new StationComponentPanelView(stationId, anyTampered(), updatedAt, views);
        }
    }

    private record ComponentTemplate(String id,
                                     String name,
                                     String description) {
    }

    private static final class ComponentRuntimeState {
        private final String componentId;
        private final String name;
        private final String description;
        private boolean tampered;

        private ComponentRuntimeState(String componentId,
                                      String name,
                                      String description,
                                      boolean tampered) {
            this.componentId = componentId;
            this.name = name;
            this.description = description;
            this.tampered = tampered;
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
            "Current hash generated from simulated components",
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
            STATUS_PENDING,
            STATUS_PENDING
        };
        private final String[] details = new String[] {"-", "-", "-", "-", "-", "-", "-", "-"};
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
                case "CurrentGoldenHashGeneratedEvent" -> markDone(4, detail);
                case "CurrentStatusHashReportedEvent" -> markDone(5, detail);
                case "GoldenSignatureVerifiedEvent" -> markDone(6, detail);
                case "GoldenSignatureVerificationFailedEvent" -> markFailed(6, detail);
                case "GoldenHashVerifiedEvent" -> {
                    markDone(7, detail);
                    finalVerdict = "VERIFIED";
                }
                case "GoldenHashTamperedEvent" -> {
                    markFailed(7, detail);
                    finalVerdict = "TAMPERED";
                }
                case "GoldenHashVerificationFailedEvent" -> {
                    markFailed(7, detail);
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

    private String normalizeStationId(String stationId, String fallback) {
        return (stationId == null || stationId.isBlank()) ? fallback : stationId;
    }

    private StationComponentState ensureComponentState(String stationId) {
        return componentStates.computeIfAbsent(stationId, StationComponentState::newState);
    }

    private void setAllComponentTamper(String stationId, boolean tampered) {
        ensureComponentState(stationId).setAllTampered(tampered);
    }

    private void setComponentTamper(String stationId, String componentId, boolean tampered) {
        ensureComponentState(stationId).setTampered(componentId, tampered);
    }

    private GeneratedHashView generateCurrentHashInternal(String stationId, boolean appendTraceEvent) {
        StationComponentState state = ensureComponentState(stationId);
        String hash = buildCanonicalHash(stationId, state.canonicalStatePayload());
        String generationSource = state.anyTampered()
            ? "COMPONENT_DELTA"
            : "HEALTHY_COMPONENT_CANONICAL_STATE";

        GeneratedHashView generated = new GeneratedHashView(
            stationId,
            hash,
            generationSource,
            state.anyTampered(),
            state.tamperedComponents(),
            Instant.now()
        );
        generatedHashes.put(stationId, generated);

        if (appendTraceEvent) {
            String summary = "Generated component baseline hash " + generated.hash()
                + " from state " + state.canonicalStatePayload();
            appendEvent("trust", "CurrentGoldenHashGeneratedEvent", stationId,
                summary,
                generated.anyTampered() ? "WARN" : "INFO");
        }
        return generated;
    }

    private String resolveHealthyBaselineHash(String stationId) {
        OnChainBaselineView baseline = onChainBaselines.get(stationId);
        if (baseline != null && baseline.expectedHash() != null && !baseline.expectedHash().isBlank()) {
            return baseline.expectedHash();
        }
        return buildCanonicalHash(stationId, canonicalHealthyStatePayload());
    }

    private String canonicalHealthyStatePayload() {
        StringBuilder builder = new StringBuilder();
        builder.append("v1|");
        for (ComponentTemplate template : DEFAULT_COMPONENTS) {
            builder.append(template.id())
                .append('=')
                .append('0')
                .append(';');
        }
        return builder.toString();
    }

    private String buildCanonicalHash(String stationId, String canonicalStatePayload) {
        String canonical = "station=" + stationId + "|" + canonicalStatePayload;
        return "0x" + sha256Hex(canonical);
    }

    private OnChainBaselineView setOnChainBaselineHashInternal(String stationId,
                                                               String onChainExpectedHash,
                                                               String source) {
        if (onChainExpectedHash == null || onChainExpectedHash.isBlank()) {
            throw new IllegalArgumentException("onChainExpectedHash must not be blank");
        }

        String normalized = "0x" + normaliseHash(onChainExpectedHash).toLowerCase();
        OnChainBaselineView baseline = new OnChainBaselineView(
            stationId,
            normalized,
            source,
            Instant.now()
        );
        onChainBaselines.put(stationId, baseline);
        return baseline;
    }

    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String normaliseHash(String hash) {
        if (hash == null) {
            return "";
        }
        String normalized = hash.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return normalized.substring(2);
        }
        return normalized;
    }
}
