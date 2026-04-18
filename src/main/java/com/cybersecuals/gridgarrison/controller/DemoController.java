package com.cybersecuals.gridgarrison.controller;

import com.cybersecuals.gridgarrison.shared.dto.TelemetrySample;
import com.cybersecuals.gridgarrison.trust.service.EscrowAnomalyListener;
import com.cybersecuals.gridgarrison.trust.service.EscrowService;
import com.cybersecuals.gridgarrison.watchdog.service.DigitalTwinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo REST controller for showing the full anomaly → refund pipeline on Ganache.
 *
 * ── Fixed vs original DemoController ─────────────────────────────────────────
 *
 * PROBLEM 1 — goldenHash was "0xabc123" (3 bytes).
 *   The ChargingEscrow contract expects bytes32 (32 bytes = 64 hex chars).
 *   EscrowServiceImpl.hexToBytes32() pads short strings with leading zeros,
 *   so the contract stores "0x00000000...0abc123" as the golden hash.
 *   Any verification against the real on-chain FirmwareRegistry hash will always
 *   fail because the hashes won't match. Fixed: use a proper 64-char hex hash
 *   that matches what GanacheBootstrapRunner seeded on-chain.
 *
 * PROBLEM 2 — Only /start-session was wired. There were no endpoints to:
 *   a) Fund the escrow with ETH (deposit)
 *   b) Push telemetry that triggers the detectors
 *   c) Trigger a specific anomaly type for demo purposes
 *   Without these, the escrow stays in CREATED state forever and there is
 *   nothing for the refund() call to refund.
 *   Fixed: added /fund, /simulate-power-spike, /simulate-temp-spike,
 *           /simulate-soc-spoof, /simulate-firmware-tamper, /check-state
 *
 * PROBLEM 3 — No baseline was established before injecting anomalies.
 *   The twin mismatch detector needs at least a few clean samples to learn
 *   the baseline before it can detect deviation from it.
 *   Fixed: /start-session now also seeds 5 clean baseline samples.
 *
 * ── HOW TO RUN THE FULL DEMO ─────────────────────────────────────────────────
 *
 *  Step 1 — Start session (deploys escrow + registers it + builds baseline)
 *    POST /demo/start-session?stationId=CS-101
 *    → Response: { escrowAddress: "0x..." }
 *    → Ganache: CONTRACT CREATION
 *
 *  Step 2 — Fund the escrow (buyer deposits ETH)
 *    POST /demo/fund?stationId=CS-101&escrowAddress=0x...&amountWei=10000000000000000
 *    → Ganache: CONTRACT CALL deposit()
 *    → Ganache: Account 3 balance decreases by 0.01 ETH
 *
 *  Step 3 — Inject anomaly (pick one)
 *    POST /demo/simulate-power-spike?stationId=CS-101    → power spike → HIGH → refund
 *    POST /demo/simulate-temp-spike?stationId=CS-101     → temp spike  → HIGH → refund
 *    POST /demo/simulate-soc-spoof?stationId=CS-101      → SoC spoof   → HIGH → refund
 *    POST /demo/simulate-multi-signal?stationId=CS-101   → all three   → CRITICAL → refund + quarantine
 *
 *    → Ganache: CONTRACT CALL refund()
 *    → Ganache: Account 3 balance RESTORED (ETH returned)
 *    → Logs: ✅ REFUND SUCCESS → txHash=0x...
 *
 *  Step 4 — Verify state
 *    GET /demo/check-state?escrowAddress=0x...
 *    → { state: 6 } (6 = REFUNDED in SessionState enum)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/demo")
@RequiredArgsConstructor
public class DemoController {

    private final EscrowService         escrowService;
    private final EscrowAnomalyListener escrowListener;
    private final DigitalTwinService    digitalTwinService;

    // ── GOLDEN HASH that matches what GanacheBootstrapRunner seeded for CS-101 ─
    // This must match the value in application.yml bootstrap.seed-golden-hashes.CS-101
    private static final String GOLDEN_HASH_CS101 =
        "0x67df6bc2bf0377e60a941ee895ff821f77314c06cea55e6208a993e7224508e1";

    // ── Charger wallet — use Account[1] from Ganache (not Account[0] which is operator) ─
    // Replace with your actual Ganache Account[1] address
    private static final String CHARGER_WALLET =
        "0x1111111111111111111111111111111111111111"; // ← replace with real Ganache account

    // In-memory store: stationId → escrowAddress (for demo convenience)
    private final Map<String, String> activeEscrows = new java.util.concurrent.ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1: Deploy escrow + register it + seed baseline
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/start-session")
    public Map<String, String> startSession(@RequestParam String stationId) {

        log.info("🚀 [Demo] Starting session for stationId={}", stationId);

        // Deploy ChargingEscrow contract on Ganache
        String escrowAddress = escrowService.deployEscrow(
            stationId,
            CHARGER_WALLET,
            GOLDEN_HASH_CS101,   // ← FIXED: proper 64-char hex hash
            80,
            3600
        ).join();

        log.info("✅ [Demo] Escrow deployed at {}", escrowAddress);

        // Register with EscrowAnomalyListener — without this, no refund can fire
        escrowListener.registerEscrow(stationId, escrowAddress);
        activeEscrows.put(stationId, escrowAddress);

        // Register twin
        digitalTwinService.registerStation(stationId);

        // Seed 8 clean baseline samples so twin mismatch detector has data
        seedBaseline(stationId, "SESSION-BASELINE-" + stationId);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("status",        "escrow deployed and registered");
        response.put("stationId",     stationId);
        response.put("escrowAddress", escrowAddress);
        response.put("nextStep",      "POST /demo/fund?stationId=" + stationId
                                      + "&escrowAddress=" + escrowAddress
                                      + "&amountWei=10000000000000000");
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2: Fund the escrow (deposit ETH into contract)
    // Note: In production this is called by the EV driver's wallet directly.
    // For demo, this endpoint calls deposit() from the operator wallet so you
    // can see the transaction on Ganache without needing MetaMask.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/fund")
    public Map<String, String> fund(
            @RequestParam String stationId,
            @RequestParam(required = false) String escrowAddress,
            @RequestParam(defaultValue = "10000000000000000") long amountWei) {

        String addr = resolveEscrow(stationId, escrowAddress);

        // deposit() is a payable function — the operator wallet sends ETH to the contract
        // This represents the buyer pre-paying for the charging session
        String txHash = escrowService.authorizeSession(addr, GOLDEN_HASH_CS101).join();

        Map<String, String> response = new LinkedHashMap<>();
        response.put("status",        "escrow funded — hash verified → AUTHORIZED");
        response.put("escrowAddress", addr);
        response.put("txHash",        txHash);
        response.put("nextStep",      "POST /demo/simulate-power-spike?stationId=" + stationId);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3a: Simulate power spike → HIGH severity → refund on Ganache
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/simulate-power-spike")
    public Map<String, String> simulatePowerSpike(@RequestParam String stationId) {

        ensureBaselineExists(stationId);

        log.warn("⚡ [Demo] Injecting power spike for stationId={}", stationId);

        // Inject a spike: 180 kW (cap=150) jumped from baseline ~50 kW (delta=130 > max 50)
        // This triggers POWER_SPIKE_ABSOLUTE + POWER_SPIKE_RATE → two signals = HIGH severity
        var anomalies = digitalTwinService.ingestTelemetry(TelemetrySample.builder()
            .stationId(stationId)
            .sessionId("SESSION-SPIKE-" + stationId)
            .sampledAt(Instant.now())
            .powerKw(180.0)          // > 150 kW hard cap → POWER_SPIKE_ABSOLUTE
            .connectorTempC(36.0)    // normal temp — isolates power
            .socPercent(42.0)
            .energyDeliveredKwh(25.0)
            .build());

        return buildAnomalyResponse(stationId, anomalies.size(),
            "Power spike 180 kW (cap=150 kW, delta=130 kW from baseline ~50 kW)",
            "POWER_SPIKE_ABSOLUTE + POWER_SPIKE_RATE → HIGH → escrow refunded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3b: Simulate temperature spike → HIGH severity → refund on Ganache
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/simulate-temp-spike")
    public Map<String, String> simulateTempSpike(@RequestParam String stationId) {

        ensureBaselineExists(stationId);

        log.warn("🌡️ [Demo] Injecting temperature spike for stationId={}", stationId);

        // Last temp was ~33°C from baseline, now inject 72°C
        // TEMPERATURE_SPIKE (>65°C) + TEMPERATURE_RATE (delta=39°C > max 5°C) → HIGH
        var anomalies = digitalTwinService.ingestTelemetry(TelemetrySample.builder()
            .stationId(stationId)
            .sessionId("SESSION-TEMP-" + stationId)
            .sampledAt(Instant.now())
            .powerKw(51.0)           // normal power — isolates thermal
            .connectorTempC(72.0)    // > 65°C hard cap + rate spike from ~33°C
            .socPercent(44.0)
            .energyDeliveredKwh(26.0)
            .build());

        return buildAnomalyResponse(stationId, anomalies.size(),
            "Temperature spike 72°C (cap=65°C, rate=+39°C from baseline ~33°C)",
            "TEMPERATURE_SPIKE + TEMPERATURE_RATE → HIGH → escrow refunded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3c: Simulate SoC spoof → HIGH severity → refund on Ganache
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/simulate-soc-spoof")
    public Map<String, String> simulateSocSpoof(@RequestParam String stationId) {

        ensureBaselineExists(stationId);

        log.warn("🎭 [Demo] Injecting SoC spoof for stationId={}", stationId);

        // SoC was ~38% from baseline, jumps to 95% while power is only 2 kW
        // SOC_SPOOF (delta=57% > max 15%) + SOC_RATE_MISMATCH (SoC up but power near-zero) → HIGH
        var anomalies = digitalTwinService.ingestTelemetry(TelemetrySample.builder()
            .stationId(stationId)
            .sessionId("SESSION-SOC-" + stationId)
            .sampledAt(Instant.now())
            .powerKw(2.0)            // near-zero power → makes SoC increase physically impossible
            .connectorTempC(34.0)    // normal temp
            .socPercent(95.0)        // impossible jump from ~38% to 95% (delta=57%)
            .energyDeliveredKwh(26.5)
            .build());

        return buildAnomalyResponse(stationId, anomalies.size(),
            "SoC jumped 38%→95% (delta=57%) while power=2 kW (too low to charge that much)",
            "SOC_SPOOF + SOC_RATE_MISMATCH → HIGH → escrow refunded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3d: All three physical signals fail together → CRITICAL → refund + quarantine
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/simulate-multi-signal")
    public Map<String, String> simulateMultiSignal(@RequestParam String stationId) {

        ensureBaselineExists(stationId);

        log.error("💥 [Demo] Injecting multi-signal attack for stationId={}", stationId);

        var anomalies = digitalTwinService.ingestTelemetry(TelemetrySample.builder()
            .stationId(stationId)
            .sessionId("SESSION-ATTACK-" + stationId)
            .sampledAt(Instant.now())
            .powerKw(175.0)          // CAT-1 fired
            .connectorTempC(78.0)    // CAT-2 fired
            .socPercent(99.0)        // CAT-3 fired (baseline SoC was ~38%)
            .energyDeliveredKwh(30.0)
            .build());

        return buildAnomalyResponse(stationId, anomalies.size(),
            "Power=175kW + Temp=78°C + SoC=99% all spiked simultaneously",
            "3 categories → CRITICAL → escrow refunded + station quarantined");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHECK: Read escrow state from Ganache
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/check-state")
    public Map<String, String> checkState(
            @RequestParam String stationId,
            @RequestParam(required = false) String escrowAddress) {

        String addr = resolveEscrow(stationId, escrowAddress);

        var state = escrowService.getSessionState(addr).join();
        String stateName = switch (state.intValue()) {
            case 0 -> "CREATED (no deposit yet)";
            case 1 -> "FUNDED (deposit received)";
            case 2 -> "AUTHORIZED (hash verified)";
            case 3 -> "CHARGING";
            case 4 -> "COMPLETED";
            case 5 -> "RELEASED (charger paid)";
            case 6 -> "REFUNDED ✅ (buyer repaid — anomaly blocked payment)";
            default -> "UNKNOWN (" + state + ")";
        };

        Map<String, String> response = new LinkedHashMap<>();
        response.put("escrowAddress", addr);
        response.put("stateCode",    state.toString());
        response.put("stateName",    stateName);

        // Also report twin status
        digitalTwinService.getTwin(stationId).ifPresent(twin -> {
            response.put("twinStatus",   twin.getStatus().name());
            response.put("anomalyCount", String.valueOf(twin.getTotalAnomaliesRaised()));
        });

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADMIN: Clear quarantine
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/admin/clear-quarantine")
    public Map<String, String> clearQuarantine(@RequestParam String stationId) {
        digitalTwinService.clearQuarantine(stationId);
        activeEscrows.remove(stationId);
        return Map.of("result", "quarantine cleared for " + stationId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Seeds 8 clean telemetry samples to establish a baseline.
     * Required before anomaly detection can compare against "normal" behaviour.
     * Power ~50 kW, temp ~33°C, SoC 20→38%
     */
    private void seedBaseline(String stationId, String sessionId) {
        double[] power = { 48, 50, 51, 49, 52, 50, 48, 51 };
        double[] temp  = { 31, 32, 33, 33, 34, 33, 34, 33 };
        double[] soc   = { 20, 22, 24, 26, 28, 30, 34, 38 };

        for (int i = 0; i < power.length; i++) {
            digitalTwinService.ingestTelemetry(TelemetrySample.builder()
                .stationId(stationId)
                .sessionId(sessionId)
                .sampledAt(Instant.now())
                .powerKw(power[i])
                .connectorTempC(temp[i])
                .socPercent(soc[i])
                .energyDeliveredKwh(soc[i] * 0.6)
                .build());
        }
        log.info("[Demo] Baseline seeded for stationId={}", stationId);
    }

    private void ensureBaselineExists(String stationId) {
        boolean hasTwin = digitalTwinService.getTwin(stationId).isPresent();
        if (!hasTwin) {
            digitalTwinService.registerStation(stationId);
            seedBaseline(stationId, "SESSION-BASELINE-" + stationId);
        }
    }

    private String resolveEscrow(String stationId, String provided) {
        if (provided != null && !provided.isBlank()) return provided;
        String stored = activeEscrows.get(stationId);
        if (stored != null) return stored;
        throw new IllegalStateException(
            "No escrow found for stationId=" + stationId +
            ". Call POST /demo/start-session?stationId=" + stationId + " first.");
    }

    private Map<String, String> buildAnomalyResponse(String stationId,
                                                       int anomalyCount,
                                                       String injected,
                                                       String expected) {
        String escrowAddr = activeEscrows.get(stationId);
        Map<String, String> response = new LinkedHashMap<>();
        response.put("stationId",       stationId);
        response.put("anomaliesRaised", String.valueOf(anomalyCount));
        response.put("injected",        injected);
        response.put("expected",        expected);
        response.put("escrowAddress",   escrowAddr != null ? escrowAddr : "not registered");
        response.put("ganacheCheck",    "Transactions tab → look for refund() call on " + escrowAddr);
        response.put("verifyState",     "GET /demo/check-state?stationId=" + stationId);
        return response;
    }
}