package com.cybersecuals.gridgarrison.visualizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeTraceServiceGoldenFlowTest {

    @Test
    void normalScenarioProducesCompletedSevenStepFlow() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.simulateNormalScenario("CS-101");
        RuntimeTraceService.RuntimeSnapshot snapshot = service.snapshot();
        RuntimeTraceService.GoldenFlowView flow = snapshot.goldenFlowByStation().get("CS-101");

        assertThat(flow).isNotNull();
        assertThat(flow.finalVerdict()).isEqualTo("VERIFIED");
        assertThat(flow.steps()).hasSize(7);
        assertThat(flow.steps()).allMatch(step -> "DONE".equals(step.status()));
    }

    @Test
    void tamperScenarioMarksFinalVerdictAsTampered() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.simulateTamperScenario("CS-TAMPER-01");
        RuntimeTraceService.RuntimeSnapshot snapshot = service.snapshot();
        RuntimeTraceService.GoldenFlowView flow = snapshot.goldenFlowByStation().get("CS-TAMPER-01");

        assertThat(flow).isNotNull();
        assertThat(flow.finalVerdict()).isEqualTo("TAMPERED");
        assertThat(flow.steps()).hasSize(7);
        assertThat(flow.steps().get(5).status()).isEqualTo("DONE");
        assertThat(flow.steps().get(6).status()).isEqualTo("FAILED");
    }
}
