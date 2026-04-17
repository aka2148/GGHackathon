package com.cybersecuals.gridgarrison.visualizer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cybersecuals.gridgarrison.orchestrator.websocket.FirmwareStatusEvent;
import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;

@RestController
@RequestMapping("/visualizer/api")
public class RuntimeTraceController {

    private final RuntimeTraceService runtimeTraceService;
    private final ApplicationEventPublisher eventPublisher;

    public RuntimeTraceController(RuntimeTraceService runtimeTraceService,
                                  ApplicationEventPublisher eventPublisher) {
        this.runtimeTraceService = runtimeTraceService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/snapshot")
    public RuntimeTraceService.RuntimeSnapshot snapshot() {
        return runtimeTraceService.snapshot();
    }

    @GetMapping("/events")
    public List<RuntimeTraceService.TraceEvent> events(
        @RequestParam(defaultValue = "80") int limit
    ) {
        return runtimeTraceService.getRecentEvents(limit);
    }

    @PostMapping("/simulate")
    public RuntimeTraceService.RuntimeSnapshot simulate(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        runtimeTraceService.simulateFullFlow(stationId);
        return runtimeTraceService.snapshot();
    }

    @PostMapping("/simulate-normal")
    public RuntimeTraceService.RuntimeSnapshot simulateNormal(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        runtimeTraceService.simulateNormalScenario(stationId);
        publishSimulatedTransaction(stationId, "START");
        return runtimeTraceService.snapshot();
    }

    @PostMapping("/simulate-tamper")
    public RuntimeTraceService.RuntimeSnapshot simulateTamper(
        @RequestParam(defaultValue = "CS-TAMPER-01") String stationId
    ) {
        runtimeTraceService.simulateTamperScenario(stationId);
        return runtimeTraceService.snapshot();
    }

    @PostMapping("/simulate-anomaly")
    public RuntimeTraceService.RuntimeSnapshot simulateAnomaly(
        @RequestParam(defaultValue = "CS-ANOM-01") String stationId
    ) {
        runtimeTraceService.simulateAnomalyScenario(stationId);
        publishSimulatedTransaction(stationId, "UPDATE");
        return runtimeTraceService.snapshot();
    }

    @PostMapping("/simulate-verify")
    public RuntimeTraceService.RuntimeSnapshot simulateVerify(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam(defaultValue = "1.0.0") String firmwareVersion,
        @RequestParam(required = false) String reportedHash
    ) {
        String selectedHash = reportedHash;
        if (selectedHash == null || selectedHash.isBlank()) {
            selectedHash = runtimeTraceService.resolveReportedHashForStation(stationId);
        }

        String payload = "{\"reportedHash\":\"" + selectedHash
            + "\",\"firmwareVersion\":\"" + firmwareVersion
            + "\",\"source\":\"visualizer-verify\"}";
        eventPublisher.publishEvent(new FirmwareStatusEvent(stationId, payload));
        return runtimeTraceService.snapshot();
    }

    @GetMapping("/components")
    public RuntimeTraceService.StationComponentPanelView components(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        return runtimeTraceService.getStationComponents(stationId);
    }

    @PostMapping("/components/tamper")
    public RuntimeTraceService.StationComponentPanelView setComponentTamper(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam String componentId,
        @RequestParam boolean tampered
    ) {
        return runtimeTraceService.updateComponentTamper(stationId, componentId, tampered);
    }

    @PostMapping("/generate-hash")
    public RuntimeTraceService.GeneratedHashView generateHash(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam(required = false) String onChainExpectedHash,
        @RequestParam(defaultValue = "FRONTEND_CONTROL_PANEL") String baselineSource
    ) {
        return runtimeTraceService.generateCurrentHashForStation(
            stationId,
            onChainExpectedHash,
            baselineSource
        );
    }

    @PostMapping("/generate-healthy-hash")
    public RuntimeTraceService.GeneratedHashView generateHealthyHash(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        try {
            return runtimeTraceService.generateHealthyHashForStation(stationId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/simulate-verify-panel")
    public RuntimeTraceService.RuntimeSnapshot simulateVerifyFromPanel(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam(defaultValue = "BLOCKCHAIN_BASELINE") String mode,
        @RequestParam(defaultValue = "1.0.0") String firmwareVersion,
        @RequestParam(required = false) String reportedHash
    ) {
        RuntimeTraceService.VerificationMode verificationMode = parseMode(mode);
        String selectedHash = reportedHash;
        if (selectedHash == null || selectedHash.isBlank()) {
            selectedHash = runtimeTraceService.resolveReportedHashForStation(stationId);
        }

        if (verificationMode == RuntimeTraceService.VerificationMode.GENERATED_BASELINE) {
            runtimeTraceService.verifyAgainstGeneratedHash(stationId, selectedHash);
            return runtimeTraceService.snapshot();
        }

        String payload = "{\"reportedHash\":\"" + selectedHash
            + "\",\"firmwareVersion\":\"" + firmwareVersion
            + "\",\"source\":\"visualizer-panel\""
            + ",\"verificationMode\":\"" + verificationMode.name() + "\"}";
        eventPublisher.publishEvent(new FirmwareStatusEvent(stationId, payload));
        return runtimeTraceService.snapshot();
    }

    @DeleteMapping("/events")
    public void clear() {
        runtimeTraceService.clear();
    }

    private void publishSimulatedTransaction(String stationId, String state) {
        String payload = "{\"sessionId\":\"SIM-" + UUID.randomUUID() + "\",\"eventType\":\""
            + state + "\",\"source\":\"visualizer\"}";
        eventPublisher.publishEvent(new TransactionEvent(stationId, payload));
    }

    private RuntimeTraceService.VerificationMode parseMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return RuntimeTraceService.VerificationMode.BLOCKCHAIN_BASELINE;
        }
        try {
            return RuntimeTraceService.VerificationMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RuntimeTraceService.VerificationMode.BLOCKCHAIN_BASELINE;
        }
    }
}
