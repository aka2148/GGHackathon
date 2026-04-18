package com.cybersecuals.gridgarrison.simulator;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ev")
@RequiredArgsConstructor
public class EvSimulatorController {

    private final EvWebSocketClient client;
    private final EvTrustVerificationClient trustVerificationClient;
    private final EvTelemetryProfileProperties telemetryProfiles;
    private final EvSimulationScenarios scenarios;
    private final EvVerificationGateState verificationGateState;
    private final AtomicReference<String> activeTransactionId = new AtomicReference<>();

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        String resolvedActiveTransactionId = getCurrentActiveTransactionId();
        body.put("connected", client.isConnected());
        body.put("stationId", client.getStationId());
        body.put("lastFirmwareHash", client.getLastFirmwareHash());
        body.put("activeTransactionId", resolvedActiveTransactionId);
        body.put("activeProfile", telemetryProfiles.getActiveProfile());
        EvVerificationGateState.Snapshot verificationSnapshot = verificationGateState.snapshot();
        body.put("verificationGateStatus", verificationSnapshot.gateStatus().name());
        body.put("chargingAllowed", verificationSnapshot.chargingAllowed());
        body.put("verificationMessage", verificationSnapshot.message());
        body.put("verificationStationId", verificationSnapshot.stationId());
        body.put("verificationReportedHash", verificationSnapshot.reportedHash());
        body.put("verificationGoldenHash", verificationSnapshot.goldenHash());
        body.put("verificationStatus", verificationSnapshot.verificationStatus());
        body.put("verificationUpdatedAt", verificationSnapshot.updatedAt());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/profiles")
    public ResponseEntity<Map<String, Object>> profiles() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeProfile", telemetryProfiles.getActiveProfile());
        body.put("availableProfiles", telemetryProfiles.getAvailableProfileNames());
        body.put("profiles", telemetryProfiles.getProfiles());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/profile")
    public ResponseEntity<Map<String, Object>> setProfile(@RequestParam String name) {
        boolean changed = telemetryProfiles.setActiveProfileIfExists(name);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", changed);
        body.put("activeProfile", telemetryProfiles.getActiveProfile());
        body.put("availableProfiles", telemetryProfiles.getAvailableProfileNames());

        if (!changed) {
            body.put("message", "Unknown profile name");
            return ResponseEntity.badRequest().body(body);
        }

        return ResponseEntity.ok(body);
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect() {
        client.connectWithBackoff();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("connected", client.isConnected());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() throws Exception {
        client.disconnect();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("connected", client.isConnected());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/transaction/start")
    public ResponseEntity<Map<String, Object>> startTransaction(
        @RequestParam(required = false) String transactionId
    ) throws Exception {
        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = transactionId == null || transactionId.isBlank()
            ? "TXN-" + UUID.randomUUID()
            : transactionId;

        client.sendTransactionStart(resolvedTransactionId);
        activeTransactionId.set(resolvedTransactionId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/transaction/meter")
    public ResponseEntity<Map<String, Object>> meterUpdate(
        @RequestParam(required = false) String transactionId,
        @RequestParam double kwh
    ) throws Exception {
        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = resolveTransactionId(transactionId);
        client.sendMeterUpdate(resolvedTransactionId, kwh);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("kwh", kwh);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/transaction/end")
    public ResponseEntity<Map<String, Object>> endTransaction(
        @RequestParam(required = false) String transactionId,
        @RequestParam(defaultValue = "0.0") double totalKwh
    ) throws Exception {
        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = resolveTransactionId(transactionId);
        client.sendTransactionEnd(resolvedTransactionId, totalKwh);
        activeTransactionId.compareAndSet(resolvedTransactionId, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("totalKwh", totalKwh);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/firmware/status")
    public ResponseEntity<Map<String, Object>> firmwareStatus(
        @RequestParam String status,
        @RequestParam String hash
    ) throws Exception {
        client.sendFirmwareStatus(status, hash);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("status", status);
        body.put("hash", hash);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/verification/request")
    public ResponseEntity<Map<String, Object>> requestVerification(
        @RequestParam(required = false) String firmwareStatus,
        @RequestParam(required = false) String firmwareVersion
    ) {
        String stationId = client.getStationId();
        EvTrustVerificationClient.RequestHashVerificationResult result = trustVerificationClient.requestHashAndVerify(
            stationId,
            firmwareStatus,
            firmwareVersion,
            client::sendFirmwareStatus
        );
        EvVerificationGateState.Snapshot snapshot = result.snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", snapshot.gateStatus() == EvVerificationGateState.GateStatus.VERIFIED);
        body.put("stationId", snapshot.stationId());
        body.put("usedReportedHash", snapshot.reportedHash());
        body.put("goldenHash", snapshot.goldenHash());
        body.put("verificationGateStatus", snapshot.gateStatus().name());
        body.put("verificationStatus", snapshot.verificationStatus());
        body.put("chargingAllowed", snapshot.chargingAllowed());
        body.put("message", snapshot.message());
        body.put("updatedAt", snapshot.updatedAt());
        body.put("deliveredHash", result.deliveredHash());
        body.put("generationSource", result.generationSource());
        body.put("anyTampered", result.anyTampered());
        body.put("tamperedComponents", result.tamperedComponents());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/scenario/status")
    public ResponseEntity<Map<String, Object>> scenarioStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", scenarios.isScenarioRunning());
        body.put("currentScenario", scenarios.getCurrentScenarioName());
        body.put("canStop", scenarios.isScenarioRunning());
        body.put("availableScenarios", new String[] {"normalCharging", "firmwareTamper", "reconnectLoop"});
        return ResponseEntity.ok(body);
    }

    @PostMapping("/scenario/run")
    public ResponseEntity<Map<String, Object>> runScenario(@RequestParam String name) {
        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        boolean started = scenarios.runScenarioAsync(name);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", started);
        body.put("running", scenarios.isScenarioRunning());
        body.put("currentScenario", scenarios.getCurrentScenarioName());

        if (!started) {
            body.put("message", "Scenario not started. It may already be running, or the name is invalid.");
            body.put("availableScenarios", new String[] {"normalCharging", "firmwareTamper", "reconnectLoop"});
            return ResponseEntity.badRequest().body(body);
        }

        return ResponseEntity.ok(body);
    }

    @PostMapping("/scenario/stop")
    public ResponseEntity<Map<String, Object>> stopScenario() {
        boolean stopped = scenarios.stopCurrentScenario();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", stopped);
        body.put("running", scenarios.isScenarioRunning());
        body.put("currentScenario", scenarios.getCurrentScenarioName());

        if (!stopped) {
            body.put("message", "No active scenario to stop.");
            return ResponseEntity.badRequest().body(body);
        }

        body.put("message", "Stop requested.");
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> verifyChargingGate() {
        if (verificationGateState.isVerifiedForCharging()) {
            return null;
        }

        EvVerificationGateState.Snapshot verificationSnapshot = verificationGateState.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", verificationGateState.blockReason());
        body.put("verificationGateStatus", verificationSnapshot.gateStatus().name());
        body.put("chargingAllowed", verificationSnapshot.chargingAllowed());
        body.put("verificationStatus", verificationSnapshot.verificationStatus());
        body.put("verificationUpdatedAt", verificationSnapshot.updatedAt());
        return ResponseEntity.status(409).body(body);
    }

    private String resolveTransactionId(String transactionId) {
        if (transactionId != null && !transactionId.isBlank()) {
            return transactionId;
        }

        String current = getCurrentActiveTransactionId();
        if (current != null && !current.isBlank()) {
            return current;
        }

        throw new IllegalArgumentException("No active transaction. Provide transactionId or start one first.");
    }

    private String getCurrentActiveTransactionId() {
        String manualTransactionId = activeTransactionId.get();
        if (manualTransactionId != null && !manualTransactionId.isBlank()) {
            return manualTransactionId;
        }

        String scenarioTransactionId = scenarios.getCurrentTransactionId();
        if (scenarioTransactionId != null && !scenarioTransactionId.isBlank()) {
            return scenarioTransactionId;
        }

        return null;
    }
}
