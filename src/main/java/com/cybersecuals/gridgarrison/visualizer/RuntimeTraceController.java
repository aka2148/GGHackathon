package com.cybersecuals.gridgarrison.visualizer;

import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @DeleteMapping("/events")
    public void clear() {
        runtimeTraceService.clear();
    }

    private void publishSimulatedTransaction(String stationId, String state) {
        String payload = "{\"sessionId\":\"SIM-" + UUID.randomUUID() + "\",\"eventType\":\""
            + state + "\",\"source\":\"visualizer\"}";
        eventPublisher.publishEvent(new TransactionEvent(stationId, payload));
    }
}
