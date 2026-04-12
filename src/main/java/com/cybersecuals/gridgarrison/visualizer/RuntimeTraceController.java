package com.cybersecuals.gridgarrison.visualizer;

import java.util.List;

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

    public RuntimeTraceController(RuntimeTraceService runtimeTraceService) {
        this.runtimeTraceService = runtimeTraceService;
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

    @DeleteMapping("/events")
    public void clear() {
        runtimeTraceService.clear();
    }
}
