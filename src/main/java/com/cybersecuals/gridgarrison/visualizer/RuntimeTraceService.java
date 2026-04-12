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

    private final Deque<TraceEvent> events = new ArrayDeque<>(MAX_EVENTS);
    private final Map<String, StationState> stations = new ConcurrentHashMap<>();

    public synchronized void appendEvent(String module,
                                         String type,
                                         String stationId,
                                         String summary,
                                         String severity) {
        String station = (stationId == null || stationId.isBlank()) ? "UNKNOWN" : stationId;
        TraceEvent event = new TraceEvent(
            Instant.now(),
            (module == null || module.isBlank()) ? "unknown" : module,
            type,
            station,
            summary == null ? "" : summary,
            (severity == null || severity.isBlank()) ? "INFO" : severity
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

        for (TraceEvent e : eventCopy) {
            byType.merge(e.type(), 1L, Long::sum);
            byModule.merge(e.module(), 1L, Long::sum);
        }

        List<StationView> stationViews = stations.values().stream()
            .map(StationState::toView)
            .sorted(Comparator.comparing(StationView::lastSeen,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        return new RuntimeSnapshot(Instant.now(), eventCopy.size(), byType, byModule, stationViews);
    }

    public synchronized void clear() {
        events.clear();
        stations.clear();
    }

    public synchronized void simulateFullFlow(String stationId) {
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
        appendEvent("trust", "GoldenHashVerifiedEvent", id,
            "Reported firmware hash matches on-chain golden hash", "INFO");
        appendEvent("watchdog", "AnomalySweepEvent", id,
            "Periodic sweep completed with no anomaly detected", "INFO");
    }

    public record TraceEvent(Instant occurredAt,
                             String module,
                             String type,
                             String stationId,
                             String summary,
                             String severity) {
    }

    public record RuntimeSnapshot(Instant capturedAt,
                                  int totalEvents,
                                  Map<String, Long> eventsByType,
                                  Map<String, Long> eventsByModule,
                                  List<StationView> stations) {
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
}
