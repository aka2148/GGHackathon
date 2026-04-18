package com.cybersecuals.gridgarrison.simulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ev")
@RequiredArgsConstructor
@Slf4j
public class EvSimulatorController {

    private final EvWebSocketClient client;
    private final EvTrustVerificationClient trustVerificationClient;
    private final EvTelemetryProfileProperties telemetryProfiles;
    private final EvSimulationScenarios scenarios;
    private final EvVerificationGateState verificationGateState;
    private final EvUserJourneyState userJourneyState;
    private final EvDigitalTwinRuntimeState digitalTwinRuntimeState;
    private final AtomicReference<String> activeTransactionId = new AtomicReference<>();
    private final AtomicReference<String> settlingTransactionId = new AtomicReference<>();
    private final AtomicReference<SettlementSummary> lastSettlement = new AtomicReference<>();
    private final AtomicReference<AutoChargeRuntime> autoChargeRuntime = new AtomicReference<>();
    private final AtomicReference<Thread> autoChargeThread = new AtomicReference<>();

    @Value("${ev.simulator.user.price-per-kwh:0.25}")
    private double userPricePerKwh;

    @Value("${ev.simulator.user.release-poll-max-attempts:24}")
    private int releasePollMaxAttempts;

    @Value("${ev.simulator.user.release-poll-interval-ms:500}")
    private long releasePollIntervalMs;

    @Value("${ev.simulator.user.authorized-poll-max-attempts:24}")
    private int authorizedPollMaxAttempts;

    @Value("${ev.simulator.user.authorized-poll-interval-ms:500}")
    private long authorizedPollIntervalMs;

    @Value("${ev.simulator.user.auto-charge-duration-ms:30000}")
    private long autoChargeDurationMs;

    @Value("${ev.simulator.user.auto-charge-tick-ms:1000}")
    private long autoChargeTickMs;

    @Value("${ev.simulator.user.auto-charge-min-target-kwh:1.0}")
    private double autoChargeMinTargetKwh;

    @Value("${ev.simulator.user.auto-charge-drift-threshold-pct:25.0}")
    private double autoChargeDriftThresholdPct;

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
        body.put("autoCharge", autoChargeRuntime.get());
        body.put("digitalTwin", digitalTwinRuntimeState.snapshot());
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

    @GetMapping("/dev/digital-twin/status")
    public ResponseEntity<Map<String, Object>> digitalTwinStatus() {
        String stationId = client.getStationId();
        try {
            EvTrustVerificationClient.WatchdogStationMetricsResponse response =
                trustVerificationClient.watchdogStationMetrics(stationId);
            if (response != null) {
                digitalTwinRuntimeState.setLastMetrics(response.metrics());
                if (response.message() != null && !response.message().isBlank()) {
                    digitalTwinRuntimeState.setWarning(response.message());
                }
            }
        } catch (Exception ex) {
            String warning = "Watchdog metrics unavailable: " + safeMessage(ex);
            digitalTwinRuntimeState.setWarning(warning);
            log.warn("[EV SIM] {}", warning);
        }

        EvDigitalTwinRuntimeState.Snapshot snapshot = digitalTwinRuntimeState.snapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("controls", snapshot.controls());
        body.put("metrics", snapshot.metrics());
        body.put("telemetry", snapshot.telemetry());
        body.put("warning", snapshot.warning());
        body.put("autoCharge", autoChargeRuntime.get());
        body.put("capturedAt", snapshot.capturedAt());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/dev/digital-twin/controls")
    public ResponseEntity<Map<String, Object>> updateDigitalTwinControls(
        @RequestParam(defaultValue = "false") boolean reset,
        @RequestParam(required = false) Double rateMultiplier,
        @RequestParam(required = false) Double energyPerUpdateKwh,
        @RequestParam(required = false) Double powerKw,
        @RequestParam(required = false) Double connectorTempC,
        @RequestParam(required = false) Double socBiasPct,
        @RequestParam(required = false) Boolean telemetryEnabled
    ) {
        EvDigitalTwinRuntimeState.ControlState controls = reset
            ? digitalTwinRuntimeState.reset()
            : digitalTwinRuntimeState.update(
                rateMultiplier,
                energyPerUpdateKwh,
                powerKw,
                connectorTempC,
                socBiasPct,
                telemetryEnabled
            );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", client.getStationId());
        body.put("reset", reset);
        body.put("controls", controls);
        body.put("metrics", digitalTwinRuntimeState.snapshot().metrics());
        body.put("message", reset
            ? "Digital twin controls reset to defaults."
            : "Digital twin controls updated.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/dev/digital-twin/sample")
    public ResponseEntity<Map<String, Object>> sendDigitalTwinSample(
        @RequestParam(required = false) String transactionId,
        @RequestParam(defaultValue = "0.0") double kwh
    ) {
        String resolvedTransactionId = resolveSampleTransactionId(transactionId);
        double profileDefault = telemetryProfiles.resolveActiveProfile().getEnergyPerUpdateKwh();
        double effectiveKwh = digitalTwinRuntimeState.resolveMeterStepKwh(kwh, profileDefault);

        EvTrustVerificationClient.WatchdogTelemetryResponse telemetry =
            publishWatchdogTelemetry(resolvedTransactionId, effectiveKwh, true, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("stationId", client.getStationId());
        body.put("transactionId", resolvedTransactionId);
        body.put("effectiveKwh", effectiveKwh);
        body.put("controls", digitalTwinRuntimeState.controls());
        body.put("telemetry", telemetry);
        body.put("metrics", digitalTwinRuntimeState.snapshot().metrics());
        body.put("warning", digitalTwinRuntimeState.snapshot().warning());
        body.put("message", "Digital twin sample sent to watchdog API.");
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
        stopAutoChargeLoop("Simulator disconnected");
        client.disconnect();
        activeTransactionId.set(null);
        settlingTransactionId.set(null);
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
        MeterProcessingOutcome outcome = processMeterUpdate(
            resolvedTransactionId,
            kwh,
            true,
            expectationForTransaction(resolvedTransactionId)
        );

        if (isRetractSeverity(outcome.telemetry())) {
            abortAutoChargeOnAnomaly(
                resolvedTransactionId,
                outcome.telemetry(),
                "Anomaly severity escalated during active charging."
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("requestedKwh", kwh);
        body.put("effectiveKwh", outcome.effectiveKwh());
        body.put("batteryPct", outcome.batteryPct());
        body.put("controls", digitalTwinRuntimeState.controls());
        body.put("telemetry", outcome.telemetry());
        body.put("digitalTwinMetrics", digitalTwinRuntimeState.snapshot().metrics());
        body.put("digitalTwinWarning", digitalTwinRuntimeState.snapshot().warning());
        body.put("autoCharge", autoChargeRuntime.get());
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
        stopAutoChargeLoop("Manual transaction end requested");
        SettlementSummary settlement;
        try {
            settlement = settleAndCompleteSession(resolvedTransactionId, totalKwh, false);
        } catch (IllegalStateException ex) {
            return settlementConflictResponse(resolvedTransactionId, ex);
        }
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
        syncGoldenHashToWatchdog(stationId, verificationSnapshot.goldenHash());

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

        if (activeEscrow == null || activeEscrow.escrowAddress() == null || activeEscrow.escrowAddress().isBlank()) {
            body.put("ok", false);
            body.put("message", "Escrow contract is not ready yet. Try polling /api/ev/user/flow/status.");
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(504).body(body);
        }

        try {
            activeEscrow = trustVerificationClient.awaitEscrowLifecycle(
                stationId,
                "AUTHORIZED",
                Math.max(1, authorizedPollMaxAttempts),
                Math.max(100L, authorizedPollIntervalMs)
            );
            syncEscrowState(activeEscrow);
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("message", "Escrow authorization poll failed: " + safeMessage(ex));
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(502).body(body);
        }

        String escrowLifecycle = normalizeLifecycleState(activeEscrow == null ? null : activeEscrow.lifecycleState());
        body.put("escrowActive", activeEscrow);
        body.put("escrowLifecycleState", escrowLifecycle);

        if (!"AUTHORIZED".equals(escrowLifecycle)) {
            body.put("ok", false);
            body.put("message", "Escrow is not ready to start charging (expected AUTHORIZED, found "
                + escrowLifecycle + ").");
            body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
            return ResponseEntity.status(409).body(body);
        }

        ResponseEntity<Map<String, Object>> blocked = verifyChargingGate();
        if (blocked != null) {
            return blocked;
        }

        String resolvedTransactionId = startTransactionInternal(transactionId);
        AutoChargeRuntime runtime = startAutoChargeSession(resolvedTransactionId, intent);

        body.put("ok", true);
        body.put("transactionId", resolvedTransactionId);
        body.put("autoCharge", runtime);
        body.put("message", "Charging simulation started for 30 seconds on the same user session.");
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
                if (latestVerdict != null) {
                    syncGoldenHashToWatchdog(stationId, latestVerdict.expectedHash());
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
        SettlementSummary settlement = reconcileSettlementWithEscrow(lastSettlement.get(), activeEscrow);

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
        body.put("settlement", settlement);
        body.put("autoCharge", autoChargeRuntime.get());
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
        stopAutoChargeLoop("Manual complete requested");
        SettlementSummary settlement;
        try {
            settlement = settleAndCompleteSession(resolvedTransactionId, totalKwh, true);
        } catch (IllegalStateException ex) {
            return settlementConflictResponse(resolvedTransactionId, ex);
        }
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
        stopAutoChargeLoop("User flow reset requested");
        Map<String, Object> body = new LinkedHashMap<>();
        String stationId = client.getStationId();

        EvTrustVerificationClient.EscrowActiveResponse escrowReset;
        EvTrustVerificationClient.WatchdogResetResponse watchdogReset = null;
        try {
            escrowReset = trustVerificationClient.resetEscrowBinding(stationId, clearIntent);
        } catch (Exception ex) {
            body.put("ok", false);
            body.put("stationId", stationId);
            body.put("message", "Failed to reset escrow binding: " + safeMessage(ex));
            return ResponseEntity.status(502).body(body);
        }

        try {
            watchdogReset = trustVerificationClient.resetWatchdogStation(stationId);
            if (watchdogReset != null && watchdogReset.metrics() != null) {
                digitalTwinRuntimeState.setLastMetrics(watchdogReset.metrics());
            }
        } catch (Exception ex) {
            digitalTwinRuntimeState.setWarning("Watchdog reset failed: " + safeMessage(ex));
        }

        activeTransactionId.set(null);
        settlingTransactionId.set(null);
        lastSettlement.set(null);
        digitalTwinRuntimeState.reset();
        userJourneyState.resetJourney(resetWallet, resetBattery);
        verificationGateState.reset(stationId, "Dashboard reset requested. Verify firmware to start a new charging session.");

        body.put("ok", true);
        body.put("stationId", stationId);
        body.put("clearIntent", clearIntent);
        body.put("resetWallet", resetWallet);
        body.put("resetBattery", resetBattery);
        body.put("escrowReset", escrowReset);
        body.put("watchdogReset", watchdogReset);
        body.put("userJourney", userJourneyState.snapshot());
        body.put("verification", verificationGateState.snapshot());
        body.put("digitalTwin", digitalTwinRuntimeState.snapshot());
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
        syncGoldenHashToWatchdog(stationId, snapshot.goldenHash());

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

    private String resolveSampleTransactionId(String transactionId) {
        if (transactionId != null && !transactionId.isBlank()) {
            return transactionId;
        }
        String active = getCurrentActiveTransactionId();
        if (active != null && !active.isBlank()) {
            return active;
        }
        return "SESSION-DEV-" + client.getStationId();
    }

    private MeterProcessingOutcome processMeterUpdate(String transactionId,
                                                      double requestedKwh,
                                                      boolean allowWarningUpdate,
                                                      DigitalTwinSessionExpectation expectation) throws Exception {
        double profileDefault = telemetryProfiles.resolveActiveProfile().getEnergyPerUpdateKwh();
        double effectiveKwh = digitalTwinRuntimeState.resolveMeterStepKwh(requestedKwh, profileDefault);

        client.sendMeterUpdate(transactionId, effectiveKwh);

        int currentBattery = userJourneyState.snapshot().batteryPct();
        int nextBattery = currentBattery;
        if (effectiveKwh > 0.0d) {
            int pctStep = Math.max(1, (int) Math.round(effectiveKwh));
            nextBattery = Math.min(100, currentBattery + pctStep);
            userJourneyState.setBatteryPct(nextBattery);
        }

        double telemetryEnergyKwh = Math.max(0.0d, effectiveKwh);
        AutoChargeRuntime runtime = autoChargeRuntime.get();
        if (runtime != null && runtime.isActive() && transactionId.equals(runtime.transactionId())) {
            telemetryEnergyKwh = round3(runtime.actualEnergyKwh() + Math.max(0.0d, effectiveKwh));
        }

        EvTrustVerificationClient.WatchdogTelemetryResponse telemetry =
            publishWatchdogTelemetry(transactionId, telemetryEnergyKwh, allowWarningUpdate, expectation);

        updateAutoChargeProgress(transactionId, expectation, effectiveKwh);

        return new MeterProcessingOutcome(effectiveKwh, nextBattery, telemetry);
    }

    private DigitalTwinSessionExpectation expectationForTransaction(String transactionId) {
        AutoChargeRuntime runtime = autoChargeRuntime.get();
        if (runtime == null || !runtime.isActive() || !transactionId.equals(runtime.transactionId())) {
            return null;
        }

        double elapsedRatio = runtime.elapsedRatio(Instant.now());
        double expectedEnergy = round3(runtime.targetEnergyKwh() * elapsedRatio);
        double expectedSoc = clampDouble(
            runtime.startBatteryPct() + ((runtime.targetSocPct() - runtime.startBatteryPct()) * elapsedRatio),
            0.0d,
            100.0d
        );

        return new DigitalTwinSessionExpectation(
            expectedEnergy,
            runtime.idealPowerKw(),
            expectedSoc,
            elapsedRatio,
            autoChargeDriftThresholdPct
        );
    }

    private void updateAutoChargeProgress(String transactionId,
                                          DigitalTwinSessionExpectation expectation,
                                          double actualDeltaKwh) {
        autoChargeRuntime.updateAndGet(runtime -> {
            if (runtime == null || !runtime.isActive() || !transactionId.equals(runtime.transactionId())) {
                return runtime;
            }

            double expectedEnergy = expectation == null
                ? runtime.expectedEnergyKwh()
                : Math.max(0.0d, expectation.expectedEnergyDeliveredKwh());
            double nextActual = round3(runtime.actualEnergyKwh() + Math.max(0.0d, actualDeltaKwh));
            return runtime.withProgress(expectedEnergy, nextActual);
        });
    }

    private AutoChargeRuntime startAutoChargeSession(String transactionId,
                                                     EvUserJourneyState.ChargeIntent intent) {
        stopAutoChargeLoop("Starting new user charging simulation");

        long durationMs = Math.max(5000L, autoChargeDurationMs);
        long tickMs = Math.max(250L, Math.min(durationMs, autoChargeTickMs));
        double targetEnergyKwh = resolveTargetEnergyKwh(intent);
        double idealPowerKw = round3(Math.max(0.0d, digitalTwinRuntimeState.controls().powerKw()));
        int startBatteryPct = userJourneyState.snapshot().batteryPct();
        int targetSocPct = intent == null
            ? Math.min(100, startBatteryPct + 30)
            : Math.max(startBatteryPct, Math.min(100, intent.targetSoc()));

        Instant startedAt = Instant.now();
        AutoChargeRuntime runtime = new AutoChargeRuntime(
            transactionId,
            startedAt,
            startedAt.plusMillis(durationMs),
            durationMs,
            tickMs,
            targetEnergyKwh,
            idealPowerKw,
            startBatteryPct,
            targetSocPct,
            0.0d,
            0.0d,
            "ACTIVE",
            "User charging simulation started.",
            startedAt,
            null
        );

        autoChargeRuntime.set(runtime);

        Thread thread = new Thread(() -> runAutoChargeSessionLoop(runtime),
            "ev-user-charge-" + transactionId);
        thread.setDaemon(true);
        autoChargeThread.set(thread);
        thread.start();

        return runtime;
    }

    private void runAutoChargeSessionLoop(AutoChargeRuntime runtime) {
        long tickCount = Math.max(1L, (long) Math.ceil((double) runtime.durationMs() / runtime.tickMs()));
        double requestedStepKwh = runtime.targetEnergyKwh() / tickCount;

        try {
            while (true) {
                AutoChargeRuntime current = autoChargeRuntime.get();
                if (current == null || !current.isActive() || !runtime.transactionId().equals(current.transactionId())) {
                    return;
                }

                if (Instant.now().isAfter(current.expectedEndAt())) {
                    break;
                }

                sleepQuietly(current.tickMs());

                current = autoChargeRuntime.get();
                if (current == null || !current.isActive() || !runtime.transactionId().equals(current.transactionId())) {
                    return;
                }

                if (!runtime.transactionId().equals(getCurrentActiveTransactionId())) {
                    transitionAutoCharge(runtime.transactionId(), "STOPPED", "Active transaction changed.", true);
                    return;
                }

                DigitalTwinSessionExpectation expectation = expectationForTransaction(runtime.transactionId());
                MeterProcessingOutcome outcome = processMeterUpdate(
                    runtime.transactionId(),
                    requestedStepKwh,
                    true,
                    expectation
                );

                if (isRetractSeverity(outcome.telemetry())) {
                    abortAutoChargeOnAnomaly(
                        runtime.transactionId(),
                        outcome.telemetry(),
                        "Digital twin anomaly detected. Escrow retraction initiated."
                    );
                    return;
                }
            }

            finalizeAutoChargeSuccess(runtime.transactionId());
        } catch (Exception ex) {
            log.warn("[EV SIM] Auto charge loop failed for tx={} reason={}", runtime.transactionId(), safeMessage(ex));
            transitionAutoCharge(runtime.transactionId(), "FAILED", safeMessage(ex), true);
        } finally {
            autoChargeThread.compareAndSet(Thread.currentThread(), null);
        }
    }

    private void finalizeAutoChargeSuccess(String transactionId) {
        AutoChargeRuntime runtime = autoChargeRuntime.get();
        if (runtime == null || !runtime.isActive() || !transactionId.equals(runtime.transactionId())) {
            return;
        }

        transitionAutoCharge(transactionId, "SETTLING", "30-second charging window completed.", false);
        try {
            double totalKwh = Math.max(0.0d, autoChargeRuntime.get().actualEnergyKwh());
            SettlementSummary settlement = settleAndCompleteSession(transactionId, totalKwh, true);
            lastSettlement.set(settlement);
            transitionAutoCharge(
                transactionId,
                "COMPLETED",
                "Auto charging completed with escrow state " + settlement.finalContractState() + ".",
                true
            );
        } catch (IllegalStateException ex) {
            transitionAutoCharge(transactionId, "FAILED", safeMessage(ex), true);
        } catch (Exception ex) {
            transitionAutoCharge(transactionId, "FAILED", safeMessage(ex), true);
        }
    }

    private void abortAutoChargeOnAnomaly(String transactionId,
                                          EvTrustVerificationClient.WatchdogTelemetryResponse telemetry,
                                          String reason) {
        AutoChargeRuntime runtime = autoChargeRuntime.get();
        if (runtime == null || !runtime.isActive() || !transactionId.equals(runtime.transactionId())) {
            return;
        }

        transitionAutoCharge(transactionId, "ANOMALY_RETRACTING", reason, false);
        activeTransactionId.compareAndSet(transactionId, null);

        try {
            EvTrustVerificationClient.EscrowActiveResponse refundedEscrow = trustVerificationClient.awaitEscrowLifecycle(
                client.getStationId(),
                "REFUNDED",
                Math.max(1, releasePollMaxAttempts),
                Math.max(100L, releasePollIntervalMs)
            );
            syncEscrowState(refundedEscrow);

            String lifecycle = normalizeLifecycleState(refundedEscrow == null ? null : refundedEscrow.lifecycleState());
            if (!"REFUNDED".equals(lifecycle)) {
                throw new IllegalStateException(
                    "Escrow lifecycle did not reach REFUNDED (current=" + lifecycle + ")"
                );
            }

            EvUserJourneyState.Snapshot beforeSnapshot = userJourneyState.snapshot();
            userJourneyState.updateTrustStatus("ANOMALY_FLAGGED");
            userJourneyState.markComplete();

            lastSettlement.set(new SettlementSummary(
                transactionId,
                Math.max(0.0d, runtime.actualEnergyKwh()),
                refundedEscrow != null && refundedEscrow.heldAmountWei() != null
                    ? refundedEscrow.heldAmountWei()
                    : beforeSnapshot.heldAmountWei(),
                0.0d,
                0.0d,
                beforeSnapshot.walletBalance(),
                "REFUNDED",
                refundedEscrow == null ? null : refundedEscrow.escrowAddress(),
                Instant.now()
            ));

            String severity = telemetry == null || telemetry.severity() == null ? "UNKNOWN" : telemetry.severity();
            transitionAutoCharge(
                transactionId,
                "ANOMALY_ABORTED",
                "Charging stopped due to " + severity + " anomaly; escrow was retracted.",
                true
            );
        } catch (Exception ex) {
            transitionAutoCharge(
                transactionId,
                "ANOMALY_ABORTED",
                "Anomaly detected but refund confirmation failed: " + safeMessage(ex),
                true
            );
        }
    }

    private void stopAutoChargeLoop(String reason) {
        AutoChargeRuntime runtime = autoChargeRuntime.get();
        if (runtime != null && runtime.isActive()) {
            transitionAutoCharge(runtime.transactionId(), "STOPPED", reason, true);
        }

        Thread thread = autoChargeThread.getAndSet(null);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    private AutoChargeRuntime transitionAutoCharge(String transactionId,
                                                   String phase,
                                                   String reason,
                                                   boolean ended) {
        return autoChargeRuntime.updateAndGet(runtime -> {
            if (runtime == null || !transactionId.equals(runtime.transactionId())) {
                return runtime;
            }
            return runtime.withPhase(phase, reason, ended);
        });
    }

    private boolean isRetractSeverity(EvTrustVerificationClient.WatchdogTelemetryResponse telemetry) {
        if (telemetry == null) {
            return false;
        }
        if (telemetry.severity() == null || telemetry.severity().isBlank()) {
            return false;
        }

        String severity = telemetry.severity().trim().toUpperCase(Locale.ROOT);
        return "MEDIUM".equals(severity)
            || "HIGH".equals(severity)
            || "CRITICAL".equals(severity);
    }

    private double resolveTargetEnergyKwh(EvUserJourneyState.ChargeIntent intent) {
        if (intent == null) {
            return Math.max(0.1d, autoChargeMinTargetKwh);
        }

        double targetFromIntent = 0.0d;
        if (intent.estimatedKwh() > 0.0d) {
            targetFromIntent = intent.estimatedKwh();
        } else if (intent.inputMode() == EvUserJourneyState.InputMode.VOLTS && intent.inputValue() > 0.0d) {
            targetFromIntent = intent.inputValue();
        } else if (intent.inputMode() == EvUserJourneyState.InputMode.MONEY
            && intent.inputValue() > 0.0d
            && userPricePerKwh > 0.0d) {
            targetFromIntent = intent.inputValue() / userPricePerKwh;
        }

        return round3(Math.max(autoChargeMinTargetKwh, targetFromIntent));
    }

    private EvTrustVerificationClient.WatchdogTelemetryResponse publishWatchdogTelemetry(String transactionId,
                                                                                          double effectiveKwh,
                                                                                          boolean allowWarningUpdate,
                                                                                          DigitalTwinSessionExpectation expectation) {
        EvDigitalTwinRuntimeState.ControlState controls = digitalTwinRuntimeState.controls();
        if (!controls.telemetryForwardingEnabled()) {
            if (allowWarningUpdate) {
                digitalTwinRuntimeState.setWarning("Watchdog telemetry forwarding is disabled in digital twin controls.");
            }
            return null;
        }

        EvTrustVerificationClient.WatchdogTelemetryRequest request = buildWatchdogTelemetryRequest(
            transactionId,
            effectiveKwh,
            controls,
            expectation
        );

        try {
            EvTrustVerificationClient.WatchdogTelemetryResponse response = trustVerificationClient.watchdogTelemetry(request);
            digitalTwinRuntimeState.setLastTelemetry(response);
            if (allowWarningUpdate && response != null && response.message() != null && !response.message().isBlank()) {
                digitalTwinRuntimeState.setWarning(response.message());
            }
            return response;
        } catch (Exception ex) {
            String warning = "Watchdog telemetry push failed: " + safeMessage(ex);
            if (allowWarningUpdate) {
                digitalTwinRuntimeState.setWarning(warning);
            }
            log.warn("[EV SIM] {}", warning);
            return null;
        }
    }

    private EvTrustVerificationClient.WatchdogTelemetryRequest buildWatchdogTelemetryRequest(
        String transactionId,
        double energyDeliveredKwh,
        EvDigitalTwinRuntimeState.ControlState controls,
        DigitalTwinSessionExpectation expectation
    ) {
        EvUserJourneyState.Snapshot userSnapshot = userJourneyState.snapshot();
        double soc = clampDouble(userSnapshot.batteryPct() + controls.socBiasPct(), 0.0d, 100.0d);
        double powerKw = controls.powerKw();
        double voltageV = 400.0d;
        Double currentA = powerKw <= 0.0d ? null : (powerKw * 1000.0d) / voltageV;

        return new EvTrustVerificationClient.WatchdogTelemetryRequest(
            client.getStationId(),
            transactionId,
            Instant.now(),
            powerKw,
            Math.max(0.0d, energyDeliveredKwh),
            expectation == null ? null : expectation.expectedEnergyDeliveredKwh(),
            expectation == null ? null : expectation.expectedPowerKw(),
            expectation == null ? null : expectation.expectedSocPercent(),
            expectation == null ? null : expectation.elapsedRatio(),
            expectation == null ? null : expectation.driftThresholdPct(),
            currentA,
            voltageV,
            controls.connectorTempC(),
            null,
            soc,
            client.getLastFirmwareHash(),
            "1"
        );
    }

    private void syncGoldenHashToWatchdog(String stationId, String goldenHash) {
        if (goldenHash == null || goldenHash.isBlank()) {
            return;
        }

        try {
            EvTrustVerificationClient.WatchdogGoldenHashSyncResponse sync =
                trustVerificationClient.syncWatchdogGoldenHash(stationId, goldenHash);
            if (sync != null) {
                digitalTwinRuntimeState.setLastMetrics(sync.metrics());
                if (sync.message() != null && !sync.message().isBlank()) {
                    digitalTwinRuntimeState.setWarning(sync.message());
                }
            }
        } catch (Exception ex) {
            log.debug("[EV SIM] Watchdog golden-hash sync skipped: {}", safeMessage(ex));
        }
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
        String inFlight = settlingTransactionId.get();
        if (inFlight != null) {
            if (inFlight.equals(transactionId)) {
                throw new IllegalStateException("Settlement is already in progress for this transaction.");
            }
            throw new IllegalStateException("Another settlement is already in progress for transaction " + inFlight + ".");
        }

        if (!settlingTransactionId.compareAndSet(null, transactionId)) {
            String current = settlingTransactionId.get();
            if (transactionId.equals(current)) {
                throw new IllegalStateException("Settlement is already in progress for this transaction.");
            }
            throw new IllegalStateException("Another settlement is already in progress.");
        }

        try {
            SettlementSummary previous = lastSettlement.get();
            if (previous != null
                && transactionId.equals(previous.transactionId())
                && "RELEASED".equalsIgnoreCase(previous.finalContractState())) {
                return previous;
            }

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
        } finally {
            settlingTransactionId.compareAndSet(transactionId, null);
        }
    }

    private ResponseEntity<Map<String, Object>> settlementConflictResponse(String transactionId,
                                                                           IllegalStateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("transactionId", transactionId);
        body.put("message", safeMessage(ex));
        body.put("userJourneyState", userJourneyState.snapshot().journeyState().name());
        return ResponseEntity.status(409).body(body);
    }

    private String normalizeLifecycleState(String lifecycleState) {
        if (lifecycleState == null || lifecycleState.isBlank()) {
            return "UNKNOWN";
        }
        return lifecycleState.trim().toUpperCase(Locale.ROOT);
    }

    private SettlementSummary reconcileSettlementWithEscrow(SettlementSummary settlement,
                                                            EvTrustVerificationClient.EscrowActiveResponse activeEscrow) {
        if (settlement == null || activeEscrow == null) {
            return settlement;
        }

        String escrowLifecycle = normalizeLifecycleState(activeEscrow.lifecycleState());
        if (!isTerminalSettlementLifecycle(escrowLifecycle)) {
            return settlement;
        }

        String settlementLifecycle = normalizeLifecycleState(settlement.finalContractState());
        if (escrowLifecycle.equals(settlementLifecycle)) {
            return settlement;
        }

        SettlementSummary reconciled = new SettlementSummary(
            settlement.transactionId(),
            settlement.totalEnergyKwh(),
            activeEscrow.heldAmountWei() == null ? settlement.heldAmountWei() : activeEscrow.heldAmountWei(),
            settlement.chargedAmount(),
            settlement.debitedAmount(),
            settlement.remainingWalletBalance(),
            escrowLifecycle,
            activeEscrow.escrowAddress() == null || activeEscrow.escrowAddress().isBlank()
                ? settlement.escrowAddress()
                : activeEscrow.escrowAddress(),
            Instant.now()
        );
        lastSettlement.set(reconciled);
        return reconciled;
    }

    private boolean isTerminalSettlementLifecycle(String lifecycleState) {
        return "RELEASED".equals(lifecycleState)
            || "REFUNDED".equals(lifecycleState);
    }

    private double roundMoney(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.min(max, Math.max(min, value));
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

    private record DigitalTwinSessionExpectation(
        double expectedEnergyDeliveredKwh,
        double expectedPowerKw,
        double expectedSocPercent,
        double elapsedRatio,
        double driftThresholdPct
    ) {
    }

    private record MeterProcessingOutcome(
        double effectiveKwh,
        int batteryPct,
        EvTrustVerificationClient.WatchdogTelemetryResponse telemetry
    ) {
    }

    private record AutoChargeRuntime(
        String transactionId,
        Instant startedAt,
        Instant expectedEndAt,
        long durationMs,
        long tickMs,
        double targetEnergyKwh,
        double idealPowerKw,
        int startBatteryPct,
        int targetSocPct,
        double expectedEnergyKwh,
        double actualEnergyKwh,
        String phase,
        String reason,
        Instant updatedAt,
        Instant endedAt
    ) {
        boolean isActive() {
            return "ACTIVE".equalsIgnoreCase(phase);
        }

        double elapsedRatio(Instant now) {
            long elapsedMs = Math.max(0L, now.toEpochMilli() - startedAt.toEpochMilli());
            if (durationMs <= 0L) {
                return 1.0d;
            }
            return Math.min(1.0d, Math.max(0.0d, (double) elapsedMs / (double) durationMs));
        }

        AutoChargeRuntime withProgress(double expectedEnergyKwh,
                                       double actualEnergyKwh) {
            return new AutoChargeRuntime(
                transactionId,
                startedAt,
                expectedEndAt,
                durationMs,
                tickMs,
                targetEnergyKwh,
                idealPowerKw,
                startBatteryPct,
                targetSocPct,
                expectedEnergyKwh,
                actualEnergyKwh,
                phase,
                reason,
                Instant.now(),
                endedAt
            );
        }

        AutoChargeRuntime withPhase(String nextPhase,
                                    String nextReason,
                                    boolean ended) {
            return new AutoChargeRuntime(
                transactionId,
                startedAt,
                expectedEndAt,
                durationMs,
                tickMs,
                targetEnergyKwh,
                idealPowerKw,
                startBatteryPct,
                targetSocPct,
                expectedEnergyKwh,
                actualEnergyKwh,
                nextPhase,
                nextReason,
                Instant.now(),
                ended ? Instant.now() : endedAt
            );
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
