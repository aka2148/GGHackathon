package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.TelemetrySample;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/watchdog/api")
class WatchdogReadController {

    private final DigitalTwinService digitalTwinService;

    WatchdogReadController(DigitalTwinService digitalTwinService) {
        this.digitalTwinService = digitalTwinService;
    }

    @GetMapping("/station-metrics")
    ResponseEntity<Map<String, Object>> stationMetrics(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        digitalTwinService.registerStation(stationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("metrics", metricsPayload(stationId));
        body.put("message", "Watchdog station metrics snapshot loaded.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/golden-hash")
    ResponseEntity<Map<String, Object>> syncGoldenHash(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam String goldenHash
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (goldenHash == null || goldenHash.isBlank()) {
            body.put("ok", false);
            body.put("stationId", stationId);
            body.put("message", "goldenHash is required.");
            return ResponseEntity.badRequest().body(body);
        }

        digitalTwinService.registerStation(stationId);
        digitalTwinService.setGoldenHash(stationId, goldenHash);

        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("goldenHash", goldenHash);
        body.put("metrics", metricsPayload(stationId));
        body.put("message", "Golden hash synced to watchdog baseline.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reset")
    ResponseEntity<Map<String, Object>> resetStation(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        digitalTwinService.registerStation(stationId);
        digitalTwinService.clearQuarantine(stationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("metrics", metricsPayload(stationId));
        body.put("message", "Watchdog station state reset.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/telemetry")
    ResponseEntity<Map<String, Object>> ingestTelemetry(@RequestBody TelemetryIngestRequest request) {
        String stationId = request.stationId() == null || request.stationId().isBlank()
            ? "CS-101"
            : request.stationId().trim();

        digitalTwinService.registerStation(stationId);

        TelemetrySample sample = TelemetrySample.builder()
            .stationId(stationId)
            .sessionId(request.sessionId() == null || request.sessionId().isBlank()
                ? "SESSION-UNKNOWN"
                : request.sessionId().trim())
            .sampledAt(request.sampledAt() == null ? Instant.now() : request.sampledAt())
            .powerKw(request.powerKw())
            .energyDeliveredKwh(request.energyDeliveredKwh())
            .currentA(request.currentA())
            .voltageV(request.voltageV())
            .connectorTempC(request.connectorTempC())
            .batteryTempC(request.batteryTempC())
            .socPercent(request.socPercent())
            .reportedFirmwareHash(request.reportedFirmwareHash())
            .connectorId(request.connectorId())
            .build();

        DigitalTwinService.TelemetryExpectation expectation = new DigitalTwinService.TelemetryExpectation(
            request.expectedEnergyDeliveredKwh(),
            request.expectedPowerKw(),
            request.expectedSocPercent(),
            request.elapsedRatio(),
            request.driftThresholdPct()
        );

        List<AnomalyReport> anomalies = digitalTwinService.ingestTelemetry(sample, expectation);
        List<String> anomalyTypes = anomalies.stream()
            .map(report -> report.getType().name())
            .distinct()
            .toList();

        StationTwinDiagnostics diagnostics = digitalTwinService.getDiagnostics(stationId).orElse(null);
        String sampleSeverity = anomalies.isEmpty()
            ? "NONE"
            : diagnostics == null
                ? "LOW"
                : diagnostics.lastSeverity();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("sessionId", sample.getSessionId());
        body.put("sampledAt", sample.getSampledAt());
        body.put("anomaliesDetected", !anomalies.isEmpty());
        body.put("anomalyCount", anomalies.size());
        body.put("anomalyTypes", anomalyTypes);
        body.put("severity", sampleSeverity);
        body.put("idealEnergyDeliveredKwh", request.expectedEnergyDeliveredKwh());
        body.put("idealPowerKw", request.expectedPowerKw());
        body.put("elapsedRatio", request.elapsedRatio());
        body.put("metrics", metricsPayload(stationId));
        body.put("message", anomalies.isEmpty()
            ? "Telemetry ingested with no anomalies."
            : "Telemetry ingested and anomalies detected.");

        return ResponseEntity.ok(body);
    }

    private Map<String, Object> metricsPayload(String stationId) {
        StationTwin twin = digitalTwinService.getTwin(stationId).orElse(null);
        StationTwinDiagnostics diagnostics = digitalTwinService.getDiagnostics(stationId).orElse(null);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("twinStatus", diagnostics == null ? "UNKNOWN" : diagnostics.twinStatus().name());
        metrics.put("totalAnomalies", diagnostics == null ? 0 : diagnostics.totalAnomaliesRaised());
        metrics.put("chargingAnomalyCount", diagnostics == null ? 0 : diagnostics.chargingAnomalyCount());
        metrics.put("powerAnomalyCount", diagnostics == null ? 0 : diagnostics.powerAnomalyCount());
        metrics.put("temperatureAnomalyCount", diagnostics == null ? 0 : diagnostics.temperatureAnomalyCount());
        metrics.put("socAnomalyCount", diagnostics == null ? 0 : diagnostics.socAnomalyCount());
        metrics.put("firmwareMismatchCount", diagnostics == null ? 0 : diagnostics.firmwareMismatchCount());
        metrics.put("stationHashChanged", diagnostics != null && diagnostics.stationHashChanged());
        metrics.put("powerSurgeDetected", diagnostics != null && diagnostics.powerSurgeDetected());
        metrics.put("lastSeverity", diagnostics == null ? "NONE" : diagnostics.lastSeverity());
        metrics.put("lastAnomalyTypes", diagnostics == null ? List.of() : diagnostics.lastAnomalyTypes());
        metrics.put("lastAnomalyAt", diagnostics == null ? null : diagnostics.lastAnomalyAt());
        metrics.put("lastHeartbeat", diagnostics == null ? null : diagnostics.lastHeartbeat());
        metrics.put("lastPowerKw", diagnostics == null ? null : diagnostics.lastPowerKw());
        metrics.put("lastTempC", diagnostics == null ? null : diagnostics.lastTempC());
        metrics.put("lastSocPercent", diagnostics == null ? null : diagnostics.lastSocPercent());
        metrics.put("goldenHash", diagnostics == null ? null : diagnostics.goldenHash());
        metrics.put("avgEnergyPerSessionKwh", twin == null ? 0.0d : twin.getAvgEnergyPerSessionKwh());
        metrics.put("recentSessions", twin == null || twin.getRecentSessions() == null ? 0 : twin.getRecentSessions().size());
        return metrics;
    }

    record TelemetryIngestRequest(
        String stationId,
        String sessionId,
        Instant sampledAt,
        Double powerKw,
        Double energyDeliveredKwh,
        Double expectedEnergyDeliveredKwh,
        Double expectedPowerKw,
        Double expectedSocPercent,
        Double elapsedRatio,
        Double driftThresholdPct,
        Double currentA,
        Double voltageV,
        Double connectorTempC,
        Double batteryTempC,
        Double socPercent,
        String reportedFirmwareHash,
        String connectorId
    ) {
    }
}
