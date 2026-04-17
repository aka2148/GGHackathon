package com.cybersecuals.gridgarrison.visualizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeTraceServiceGoldenFlowTest {

    @Test
    void normalScenarioProducesCompletedEightStepFlow() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.simulateNormalScenario("CS-101");
        RuntimeTraceService.RuntimeSnapshot snapshot = service.snapshot();
        RuntimeTraceService.GoldenFlowView flow = snapshot.goldenFlowByStation().get("CS-101");

        assertThat(flow).isNotNull();
        assertThat(flow.finalVerdict()).isEqualTo("VERIFIED");
        assertThat(flow.steps()).hasSize(8);
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
        assertThat(flow.steps()).hasSize(8);
        assertThat(flow.steps().get(6).status()).isEqualTo("DONE");
        assertThat(flow.steps().get(7).status()).isEqualTo("FAILED");
    }

    @Test
    void componentTamperChangesGeneratedHash() {
        RuntimeTraceService service = new RuntimeTraceService();

        RuntimeTraceService.GeneratedHashView healthy = service.generateCurrentHashForStation("CS-202");
        service.updateComponentTamper("CS-202", "power-supply", true);
        RuntimeTraceService.GeneratedHashView tampered = service.generateCurrentHashForStation("CS-202");

        assertThat(healthy.hash()).startsWith("0x");
        assertThat(healthy.hash().length()).isEqualTo(66);
        assertThat(healthy.generationSource()).isEqualTo("HEALTHY_COMPONENT_CANONICAL_STATE");
        assertThat(tampered.hash()).isNotEqualTo(healthy.hash());
        assertThat(tampered.generationSource()).isEqualTo("COMPONENT_DELTA");
        assertThat(tampered.anyTampered()).isTrue();
        assertThat(tampered.tamperedComponents()).contains("power-supply");
    }

    @Test
    void healthyHashRemainsCanonicalEvenWhenOnChainBaselineIsProvided() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.setOnChainBaselineHash("CS-404", "0xfeedbeef", "TEST_FIXTURE");
        RuntimeTraceService.GeneratedHashView healthy = service.generateCurrentHashForStation("CS-404");

        assertThat(healthy.hash()).isNotEqualTo("0xfeedbeef");
        assertThat(healthy.generationSource()).isEqualTo("HEALTHY_COMPONENT_CANONICAL_STATE");
    }

    @Test
    void healthyBaselineCandidateFailsWhenAnyModuleIsTampered() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.updateComponentTamper("CS-505", "connector-relay", true);

        assertThatThrownBy(() -> service.generateHealthyHashForStation("CS-505"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("modules are abnormal");
    }

    @Test
    void generatedBaselineVerificationReportsMismatchWhenHashesDiffer() {
        RuntimeTraceService service = new RuntimeTraceService();

        service.updateComponentTamper("CS-303", "connector-relay", true);
        service.generateCurrentHashForStation("CS-303");
        RuntimeTraceService.GeneratedVerificationView comparison =
            service.verifyAgainstGeneratedHash("CS-303", "0xabc123");

        assertThat(comparison.mode()).isEqualTo("GENERATED_BASELINE");
        assertThat(comparison.verdict()).isEqualTo("MISMATCH");
    }
}
