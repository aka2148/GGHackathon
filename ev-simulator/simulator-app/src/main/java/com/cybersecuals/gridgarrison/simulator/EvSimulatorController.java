package com.cybersecuals.gridgarrison.simulator;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.time.Instant;
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
    private final EvUserJourneyState userJourneyState;
    private final AtomicReference<String> activeTransactionId = new AtomicReference<>();
    private final AtomicReference<SettlementSummary> lastSettlement = new AtomicReference<>();

    @Value("${ev.simulator.user.price-per-kwh:0.25}")
    private double userPricePerKwh;

    @Value("${ev.simulator.user.release-poll-max-attempts:24}")
    private int releasePollMaxAttempts;

    @Value("${ev.simulator.user.release-poll-interval-ms:500}")
    private long releasePollIntervalMs;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        String resolvedActiveTransactionId = getCurrentActiveTransactionId();
        body.put("stationId", client.getStationId());
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

        EvUserJourneyState.Snapshot userSnapshot = userJourneyState.snapshot();
        body.put("userJourneyState", userSnapshot.journeyState().name());
        body.put("trustStatus", userSnapshot.trustStatus());
        body.put("escrowStatus", userSnapshot.escrowStatus());
        body.put("escrowAddress", userSnapshot.escrowAddress());
        body.put("escrowAddressShort", userSnapshot.escrowAddressShort());
        body.put("heldAmountWei", userSnapshot.heldAmountWei());
        body.put("batteryPct", userSnapshot.batteryPct());
        body.put("walletBalance", userSnapshot.walletBalance());
        body.put("userChargeIntent", userSnapshot.chargeIntent());
        body.put("lastSettlement", lastSettlement.get());
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
        if (client.isConnected()) {
            userJourneyState.markHandshakeStarted();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("connected", client.isConnected());
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() throws Exception {
        client.disconnect();
        activeTransactionId.set(null);
        scenarios.clearCurrentTransactionId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("connected", client.isConnected());
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
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
        userJourneyState.markCharging();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
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

        int currentBattery = userJourneyState.snapshot().batteryPct();
        if (kwh > 0.0d) {
            userJourneyState.setBatteryPct(currentBattery + 1);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("kwh", kwh);
        body.put("batteryPct", userJourneyState.snapshot().batteryPct());
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
        SettlementSummary settlement = settleAndCompleteSession(resolvedTransactionId, totalKwh, false);
        lastSettlement.set(settlement);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", "RELEASED".equalsIgnoreCase(settlement.finalContractState()));
        body.put("transactionId", resolvedTransactionId);
        body.put("totalKwh", settlement.totalEnergyKwh());
        body.put("settlement", settlement);
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/intent")
    public ResponseEntity<Map<String, Object>> setUserIntent(
        @RequestParam(defaultValue = "MONEY") String inputMode,
        @RequestParam double inputValue,
        @RequestParam(required = false) Double estimatedKwh,
        @RequestParam(required = false) BigInteger holdAmountWei,
        @RequestParam(defaultValue = "80") int targetSoc,
        @RequestParam(required = false) Long timeoutSeconds,
        @RequestParam(required = false) String chargerWallet,
        @RequestParam(defaultValue = "true") boolean syncTrust
    ) {
        EvUserJourneyState.ChargeIntent intent = buildChargeIntent(
            inputMode,
            inputValue,
            estimatedKwh,
            holdAmountWei,
            targetSoc
        );

        userJourneyState.updateChargeIntent(intent);
        userJourneyState.markContractCreating();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("intent", intent);
        body.put("stationId", client.getStationId());

        if (syncTrust) {
            try {
                EvTrustVerificationClient.EscrowIntentResponse trustIntent = trustVerificationClient.submitEscrowIntent(
                    client.getStationId(),
                    intent.holdAmountWei(),
                    intent.targetSoc(),
                    timeoutSeconds,
                    chargerWallet
                );
                body.put("trustIntent", trustIntent);
            } catch (Exception ex) {
                body.put("ok", false);
                body.put("message", "Failed to submit escrow intent to trust API: " + safeMessage(ex));
                return ResponseEntity.status(502).body(body);
            }
        }

        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/flow/start")
    public ResponseEntity<Map<String, Object>> startUserFlow(
        @RequestParam(defaultValue = "MONEY") String inputMode,
        @RequestParam double inputValue,
        @RequestParam(required = false) Double estimatedKwh,
        @RequestParam(required = false) BigInteger holdAmountWei,
        @RequestParam(defaultValue = "80") int targetSoc,
        @RequestParam(required = false) Long timeoutSeconds,
        @RequestParam(required = false) String chargerWallet,
        @RequestParam(required = false) String transactionId,
        @RequestParam(required = false) String firmwareStatus,
        @RequestParam(required = false) String firmwareVersion
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        String stationId = client.getStationId();

        EvUserJourneyState.ChargeIntent intent = buildChargeIntent(
            inputMode,
            inputValue,
            estimatedKwh,
            holdAmountWei,
            targetSoc
        );
        userJourneyState.updateChargeIntent(intent);
        userJourneyState.markContractCreating();

        EvTrustVerificationClient.EscrowIntentResponse trustIntent;
        try {
            trustIntent = trustVerificationClient.submitEscrowIntent(
                stationId,
                intent.holdAmountWei(),
                intent.targetSoc(),
                timeoutSeconds,
                chargerWallet
            );
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("stationId", stationId);
            body.put("connected", client.isConnected());
            body.put("intent", intent);
            body.put("message", "Failed to submit escrow intent: " + safeMessage(ex));
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(502).body(body);
        }

        if (!client.isConnected()) {
            client.connectWithBackoff();
        }

        if (!client.isConnected()) {
            body.put("ok", false);
            body.put("stationId", stationId);
            body.put("message", "Unable to connect EV simulator to backend gateway.");
            body.put("connected", false);
            body.put("intent", intent);
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(503).body(body);
        }

        userJourneyState.markHandshakeStarted();

        EvTrustVerificationClient.RequestHashVerificationResult verification =
            trustVerificationClient.requestHashAndVerify(
                stationId,
                firmwareStatus,
                firmwareVersion,
                client::sendFirmwareStatus
            );

        EvVerificationGateState.Snapshot verificationSnapshot = verification.snapshot();
        if (verificationSnapshot.gateStatus() == EvVerificationGateState.GateStatus.PENDING) {
            verificationSnapshot = awaitVerificationGateResolution();
        }
        userJourneyState.updateTrustStatus(verificationSnapshot.verificationStatus());
        if (verificationSnapshot.gateStatus() == EvVerificationGateState.GateStatus.VERIFIED) {
            userJourneyState.markVerified(verificationSnapshot.verificationStatus());
        }

        body.put("stationId", stationId);
        body.put("connected", true);
        body.put("intent", intent);
        body.put("trustIntent", trustIntent);
        body.put("verificationGateStatus", verificationSnapshot.gateStatus().name());
        body.put("verificationStatus", verificationSnapshot.verificationStatus());
        body.put("chargingAllowed", verificationSnapshot.chargingAllowed());
        body.put("verificationMessage", verificationSnapshot.message());
        body.put("verificationUpdatedAt", verificationSnapshot.updatedAt());

        if (verificationSnapshot.gateStatus() != EvVerificationGateState.GateStatus.VERIFIED) {
            body.put("ok", false);
            body.put("message", "User flow stopped because firmware trust verification did not pass.");
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(409).body(body);
        }

        EvTrustVerificationClient.EscrowActiveResponse activeEscrow;
        try {
            activeEscrow = trustVerificationClient.awaitEscrowActive(stationId);
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("message", "Escrow active status poll failed: " + safeMessage(ex));
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(502).body(body);
        }

        syncEscrowState(activeEscrow);
        body.put("escrowActive", activeEscrow);

        if (activeEscrow == null || activeEscrow.escrowAddress() == null || activeEscrow.escrowAddress().isBlank()) {
            body.put("ok", false);
            body.put("message", "Escrow contract is not ready yet. Try polling /api/ev/user/flow/status.");
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(504).body(body);
        }

        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = startTransactionInternal(transactionId);
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/user/flow/status")
    public ResponseEntity<Map<String, Object>> userFlowStatus(
        @RequestParam(defaultValue = "true") boolean refreshTrust
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        String stationId = client.getStationId();

        EvTrustVerificationClient.LatestVerdictResponse latestVerdict = null;
        EvTrustVerificationClient.EscrowActiveResponse activeEscrow = null;

        if (refreshTrust) {
            try {
                latestVerdict = trustVerificationClient.latestVerdict(stationId);
                if (latestVerdict != null && latestVerdict.verificationStatus() != null) {
                    userJourneyState.updateTrustStatus(latestVerdict.verificationStatus());
                }
            } catch (Exception ex) {
                body.put("trustRefreshError", safeMessage(ex));
            }

            try {
                activeEscrow = trustVerificationClient.escrowActive(stationId);
                syncEscrowState(activeEscrow);
            } catch (Exception ex) {
                body.put("escrowRefreshError", safeMessage(ex));
            }
        }

        EvVerificationGateState.Snapshot verificationSnapshot = verificationGateState.snapshot();
        EvUserJourneyState.Snapshot userSnapshot = userJourneyState.snapshot();

        body.put("ok", true);
        body.put("connected", client.isConnected());
        body.put("stationId", stationId);
        body.put("activeTransactionId", getCurrentActiveTransactionId());
        body.put("verificationGateStatus", verificationSnapshot.gateStatus().name());
        body.put("chargingAllowed", verificationSnapshot.chargingAllowed());
        body.put("verificationStatus", verificationSnapshot.verificationStatus());
        body.put("verificationMessage", verificationSnapshot.message());
        body.put("verificationUpdatedAt", verificationSnapshot.updatedAt());
        body.put("journey", userSnapshot);
        body.put("latestVerdict", latestVerdict);
        body.put("escrowActive", activeEscrow);
        body.put("settlement", lastSettlement.get());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/flow/complete")
    public ResponseEntity<Map<String, Object>> completeUserFlow(
        @RequestParam(required = false) String transactionId,
        @RequestParam(defaultValue = "0.0") double totalKwh
    ) throws Exception {
        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = resolveTransactionId(transactionId);
        SettlementSummary settlement = settleAndCompleteSession(resolvedTransactionId, totalKwh, true);
        lastSettlement.set(settlement);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", "RELEASED".equalsIgnoreCase(settlement.finalContractState()));
        body.put("transactionId", resolvedTransactionId);
        body.put("settlement", settlement);
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());

        if (!"RELEASED".equalsIgnoreCase(settlement.finalContractState())) {
            body.put("message", "Settlement did not reach RELEASED within poll window.");
            return ResponseEntity.status(504).body(body);
        }

        body.put("message", "Settlement completed and funds released.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/flow/reset")
    public ResponseEntity<Map<String, Object>> resetUserFlow(
        @RequestParam(defaultValue = "true") boolean clearIntent,
        @RequestParam(defaultValue = "false") boolean resetWallet,
        @RequestParam(defaultValue = "false") boolean resetBattery
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        String stationId = client.getStationId();

        EvTrustVerificationClient.EscrowActiveResponse escrowReset;
        try {
            escrowReset = trustVerificationClient.resetEscrowBinding(stationId, clearIntent);
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("stationId", stationId);
            body.put("message", "Failed to reset escrow binding: " + safeMessage(ex));
            return ResponseEntity.status(502).body(body);
        }

        activeTransactionId.set(null);
        lastSettlement.set(null);
        userJourneyState.resetJourney(resetWallet, resetBattery);
        verificationGateState.reset(stationId, "Dashboard reset requested. Verify firmware to start a new charging session.");

        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("clearIntent", clearIntent);
        body.put("resetWallet", resetWallet);
        body.put("resetBattery", resetBattery);
        body.put("escrowReset", escrowReset);
        body.put("userJourney", userJourneyState.snapshot());
        body.put("verification", verificationGateState.snapshot());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/user/wallet")
    public ResponseEntity<Map<String, Object>> userWallet() {
        EvUserJourneyState.Snapshot snapshot = userJourneyState.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("walletBalance", snapshot.walletBalance());
        body.put("batteryPct", snapshot.batteryPct());
        body.put("stationId", client.getStationId());
        body.put("updatedAt", snapshot.updatedAt());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/user/wallet/topup")
    public ResponseEntity<Map<String, Object>> topUpWallet(@RequestParam double amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (amount <= 0.0d) {
            body.put("ok", false);
            body.put("message", "Top-up amount must be greater than zero.");
            return ResponseEntity.badRequest().body(body);
        }

        double nextBalance = userJourneyState.snapshot().walletBalance() + amount;
        userJourneyState.setWalletBalance(nextBalance);

        body.put("ok", true);
        body.put("amountAdded", amount);
        body.put("walletBalance", userJourneyState.snapshot().walletBalance());
        body.put("updatedAt", userJourneyState.snapshot().updatedAt());
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

        userJourneyState.updateTrustStatus(snapshot.verificationStatus());
        if (snapshot.gateStatus() == EvVerificationGateState.GateStatus.VERIFIED) {
            userJourneyState.markVerified(snapshot.verificationStatus());
        }

        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/scenario/status")
    public ResponseEntity<Map<String, Object>> scenarioStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", scenarios.isScenarioRunning());
        body.put("currentScenario", scenarios.getCurrentScenarioName());
        body.put("canStop", scenarios.isScenarioRunning());
        body.put("availableScenarios", scenarios.getAvailableScenarioNames());
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
            body.put("availableScenarios", scenarios.getAvailableScenarioNames());
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

        if (scenarios.isScenarioRunning()) {
            String scenarioTransactionId = scenarios.getCurrentTransactionId();
            if (scenarioTransactionId != null && !scenarioTransactionId.isBlank()) {
                return scenarioTransactionId;
            }
        }

        if (client.isConnected() && activeTransactionId.get() == null) {
            return null;
        }

        String scenarioTransactionId = scenarios.getCurrentTransactionId();
        if (scenarioTransactionId != null && !scenarioTransactionId.isBlank() && scenarios.isScenarioRunning()) {
            return scenarioTransactionId;
        }

        return null;
    }

    private String startTransactionInternal(String transactionId) throws Exception {
        String resolvedTransactionId = transactionId == null || transactionId.isBlank()
            ? "TXN-" + UUID.randomUUID()
            : transactionId;

        client.sendTransactionStart(resolvedTransactionId);
        activeTransactionId.set(resolvedTransactionId);
        lastSettlement.set(null);
        userJourneyState.markCharging();
        return resolvedTransactionId;
    }

    private SettlementSummary settleAndCompleteSession(String transactionId,
                                                       double totalKwh,
                                                       boolean debitWallet) throws Exception {
        double safeTotalKwh = Math.max(0.0d, totalKwh);
        EvUserJourneyState.Snapshot beforeSettlement = userJourneyState.snapshot();

        userJourneyState.markSettling();
        client.sendTransactionEnd(transactionId, safeTotalKwh);
        activeTransactionId.compareAndSet(transactionId, null);

        EvTrustVerificationClient.EscrowActiveResponse activeEscrow = trustVerificationClient.awaitEscrowLifecycle(
            client.getStationId(),
            "RELEASED",
            Math.max(1, releasePollMaxAttempts),
            Math.max(100L, releasePollIntervalMs)
        );
        syncEscrowState(activeEscrow);

        String finalContractState = activeEscrow == null || activeEscrow.lifecycleState() == null
            ? "UNKNOWN"
            : activeEscrow.lifecycleState().trim().toUpperCase();

        BigInteger heldAmountWei = activeEscrow != null && activeEscrow.heldAmountWei() != null
            ? activeEscrow.heldAmountWei()
            : beforeSettlement.heldAmountWei();

        double chargedAmount = roundMoney(safeTotalKwh * Math.max(0.0d, userPricePerKwh));
        double debitedAmount = debitWallet ? roundMoney(Math.min(beforeSettlement.walletBalance(), chargedAmount)) : 0.0d;
        double remainingWalletBalance = debitWallet
            ? roundMoney(Math.max(0.0d, beforeSettlement.walletBalance() - debitedAmount))
            : roundMoney(beforeSettlement.walletBalance());

        if (debitWallet) {
            userJourneyState.setWalletBalance(remainingWalletBalance);
        }

        if ("RELEASED".equals(finalContractState)) {
            userJourneyState.markComplete();
        } else {
            userJourneyState.markSettling();
        }

        return new SettlementSummary(
            transactionId,
            safeTotalKwh,
            heldAmountWei,
            chargedAmount,
            debitedAmount,
            remainingWalletBalance,
            finalContractState,
            activeEscrow == null ? null : activeEscrow.escrowAddress(),
            Instant.now()
        );
    }

    private double roundMoney(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private EvUserJourneyState.ChargeIntent buildChargeIntent(String inputMode,
                                                              double inputValue,
                                                              Double estimatedKwh,
                                                              BigInteger holdAmountWei,
                                                              int targetSoc) {
        EvUserJourneyState.InputMode mode = parseInputMode(inputMode);
        double safeInputValue = Math.max(0.0d, inputValue);
        double safeEstimatedKwh = Math.max(0.0d, estimatedKwh == null ? 0.0d : estimatedKwh);

        BigInteger safeHoldAmount = holdAmountWei == null ? BigInteger.ZERO : holdAmountWei;
        if (safeHoldAmount.signum() < 0) {
            safeHoldAmount = BigInteger.ZERO;
        }

        int safeTargetSoc = Math.max(1, Math.min(100, targetSoc));
        return new EvUserJourneyState.ChargeIntent(
            mode,
            safeInputValue,
            safeEstimatedKwh,
            safeHoldAmount,
            safeTargetSoc,
            Instant.now()
        );
    }

    private void syncEscrowState(EvTrustVerificationClient.EscrowActiveResponse activeEscrow) {
        if (activeEscrow == null) {
            return;
        }

        userJourneyState.updateEscrowStatus(
            activeEscrow.lifecycleState(),
            activeEscrow.escrowAddress(),
            activeEscrow.heldAmountWei()
        );

        EvUserJourneyState.Snapshot snapshot = userJourneyState.snapshot();
        if (activeEscrow.escrowAddress() == null || activeEscrow.escrowAddress().isBlank()) {
            return;
        }

        if (snapshot.journeyState() == EvUserJourneyState.JourneyState.CONTRACT_CREATING
            || snapshot.journeyState() == EvUserJourneyState.JourneyState.VERIFIED
            || snapshot.journeyState() == EvUserJourneyState.JourneyState.HANDSHAKING) {
            userJourneyState.markContractCreated(
                activeEscrow.lifecycleState(),
                activeEscrow.escrowAddress(),
                activeEscrow.heldAmountWei()
            );
        }
    }

    private String safeMessage(Exception exception) {
        if (exception == null) {
            return "Unknown error";
        }
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message;
    }

    private EvVerificationGateState.Snapshot awaitVerificationGateResolution() {
        EvVerificationGateState.Snapshot current = verificationGateState.snapshot();
        int maxAttempts = 12;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (current.gateStatus() == EvVerificationGateState.GateStatus.VERIFIED
                || current.gateStatus() == EvVerificationGateState.GateStatus.FAILED) {
                return current;
            }
            sleepQuietly(250L);
            current = verificationGateState.snapshot();
        }
        return current;
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private EvUserJourneyState.InputMode parseInputMode(String inputMode) {
        if (inputMode == null || inputMode.isBlank()) {
            return EvUserJourneyState.InputMode.MONEY;
        }
        try {
            return EvUserJourneyState.InputMode.valueOf(inputMode.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return EvUserJourneyState.InputMode.MONEY;
        }
    }

    private record SettlementSummary(
        String transactionId,
        double totalEnergyKwh,
        BigInteger heldAmountWei,
        double chargedAmount,
        double debitedAmount,
        double remainingWalletBalance,
        String finalContractState,
        String escrowAddress,
        Instant settledAt
    ) {
    }
}
