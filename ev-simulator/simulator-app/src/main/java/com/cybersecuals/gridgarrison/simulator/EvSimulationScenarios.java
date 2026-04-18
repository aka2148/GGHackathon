package com.cybersecuals.gridgarrison.simulator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Automated scenario executions for demo purposes.
 * Simulates realistic charging patterns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvSimulationScenarios {

    private static final String NORMAL_CHARGING_SCENARIO = "normalCharging";
    private static final String FIRMWARE_TAMPER_SCENARIO = "firmwareTamper";
    private static final String RECONNECT_LOOP_SCENARIO = "reconnectLoop";
    private static final String ISO_PNC_HAPPY_PATH_SCENARIO = "isoPnCHappyPath";
    private static final String ISO_PNC_CERT_MISSING_SCENARIO = "isoPnCCertMissing";

    private static final List<String> AVAILABLE_SCENARIOS = List.of(
        NORMAL_CHARGING_SCENARIO,
        FIRMWARE_TAMPER_SCENARIO,
        RECONNECT_LOOP_SCENARIO,
        ISO_PNC_HAPPY_PATH_SCENARIO,
        ISO_PNC_CERT_MISSING_SCENARIO
    );

    private final EvWebSocketClient client;
    private final EvTelemetryProfileProperties telemetryProfiles;

    @Value("${ev.simulator.auto-scenarios:false}")
    private boolean autoScenariosEnabled;

    private volatile boolean chargingActive = false;
    private volatile String currentScenarioName;
    private volatile boolean stopRequested = false;
    private volatile Thread currentScenarioThread;
    private String currentTransactionId;
    private double currentMeterValue = 0.0;

    public boolean isScenarioRunning() {
        return chargingActive;
    }

    public String getCurrentScenarioName() {
        return currentScenarioName;
    }

    public String getCurrentTransactionId() {
        return currentTransactionId;
    }

    public String[] getAvailableScenarioNames() {
        return AVAILABLE_SCENARIOS.toArray(String[]::new);
    }

    public void clearCurrentTransactionId() {
        currentTransactionId = null;
        currentMeterValue = 0.0;
    }

    public boolean stopCurrentScenario() {
        if (!chargingActive) {
            return false;
        }
        stopRequested = true;
        Thread thread = currentScenarioThread;
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }

    public boolean runScenarioAsync(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return false;
        }
        if (!isValidScenarioName(scenarioName)) {
            return false;
        }

        synchronized (this) {
            if (chargingActive) {
                return false;
            }
            chargingActive = true;
            stopRequested = false;
            currentScenarioName = scenarioName;
        }

        Thread scenarioThread = new Thread(() -> {
            try {
                switch (scenarioName) {
                    case NORMAL_CHARGING_SCENARIO -> runNormalChargingScenario();
                    case FIRMWARE_TAMPER_SCENARIO -> runFirmwareTamperScenario();
                    case RECONNECT_LOOP_SCENARIO -> runReconnectLoopScenario();
                    case ISO_PNC_HAPPY_PATH_SCENARIO -> runIsoPnCHappyPathScenario();
                    case ISO_PNC_CERT_MISSING_SCENARIO -> runIsoPnCCertMissingScenario();
                    default -> log.warn("Unknown scenario '{}'. Expected one of: {}", scenarioName, AVAILABLE_SCENARIOS);
                }
            } catch (ScenarioStoppedException e) {
                log.info("Scenario '{}' stopped", scenarioName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Scenario '{}' interrupted", scenarioName);
            } catch (Exception e) {
                log.error("Scenario '{}' failed", scenarioName, e);
            } finally {
                currentScenarioThread = null;
                stopRequested = false;
                chargingActive = false;
                currentScenarioName = null;
                clearCurrentTransactionId();
            }
        }, "ev-scenario-" + scenarioName);
        scenarioThread.setDaemon(true);
        currentScenarioThread = scenarioThread;
        scenarioThread.start();
        return true;
    }

    private boolean isValidScenarioName(String scenarioName) {
        return AVAILABLE_SCENARIOS.contains(scenarioName);
    }

    private void checkStopRequested() {
        if (stopRequested || Thread.currentThread().isInterrupted()) {
            throw new ScenarioStoppedException();
        }
    }

    /**
     * Scenario 1: Normal charging session (15 sec boot delay, then 60 sec session).
     */
    @Scheduled(initialDelayString = "15000", fixedDelayString = "120000")
    public void scenario_normalCharging() throws Exception {
        if (!autoScenariosEnabled) {
            return;
        }
        if (chargingActive) {
            log.debug("Charge session already active, skipping");
            return;
        }
        chargingActive = true;
        currentScenarioName = "normalCharging";
        try {
            runNormalChargingScenario();
        } finally {
            chargingActive = false;
            currentScenarioName = null;
            clearCurrentTransactionId();
        }
    }

    private void runNormalChargingScenario() throws Exception {
        log.info("🚗 SCENARIO: Normal Charging Session");
        EvTelemetryProfileProperties.TelemetryProfile profile = telemetryProfiles.resolveActiveProfile();
        checkStopRequested();

        long meterUpdateIntervalMs = Math.max(1000, profile.getMeterUpdateIntervalMs());
        long sessionDurationMs = Math.max(meterUpdateIntervalMs, profile.getSessionDurationMs());
        int updateCount = Math.max(1, (int) (sessionDurationMs / meterUpdateIntervalMs));

        log.info(
            "Using telemetry profile '{}' (heartbeat={}ms, meter={}ms, duration={}ms, step={}kWh)",
            telemetryProfiles.getActiveProfile(),
            profile.getHeartbeatIntervalMs(),
            meterUpdateIntervalMs,
            sessionDurationMs,
            profile.getEnergyPerUpdateKwh()
        );

        currentTransactionId = "TXN-" + UUID.randomUUID();
        currentMeterValue = 0.0;

        // Send firmware with normal (golden) hash
        String goldenHash = "abc123def456ghi789jkl012mno345pqr";
        client.sendFirmwareStatus("Downloaded", goldenHash);
        checkStopRequested();

        // Start transaction
        client.sendTransactionStart(currentTransactionId);

        // Meter updates by active profile
        for (int i = 0; i < updateCount; i++) {
            Thread.sleep(meterUpdateIntervalMs);
            checkStopRequested();
            currentMeterValue += profile.getEnergyPerUpdateKwh();
            client.sendMeterUpdate(currentTransactionId, currentMeterValue);
        }

        // End transaction
        checkStopRequested();
        client.sendTransactionEnd(currentTransactionId, currentMeterValue);
    }

    /**
     * Scenario 2: Firmware Tamper (wrong hash).
     */
    @Scheduled(initialDelayString = "100000", fixedDelayString = "120000")
    public void scenario_firmwareTamper() throws Exception {
        if (!autoScenariosEnabled) {
            return;
        }
        if (chargingActive) {
            log.debug("Charge session already active, skipping");
            return;
        }
        chargingActive = true;
        currentScenarioName = "firmwareTamper";
        try {
            runFirmwareTamperScenario();
        } finally {
            chargingActive = false;
            currentScenarioName = null;
            clearCurrentTransactionId();
        }
    }

    private void runFirmwareTamperScenario() throws Exception {
        log.info("🚗 SCENARIO: Firmware Tamper Detected");
        EvTelemetryProfileProperties.TelemetryProfile profile = telemetryProfiles.resolveActiveProfile();
        long meterUpdateIntervalMs = Math.max(1000, profile.getMeterUpdateIntervalMs());
        checkStopRequested();

        currentTransactionId = "TXN-TAMPER-" + UUID.randomUUID();
        currentMeterValue = 0.0;

        // Send firmware with tampered hash
        String tamperedHash = "xxx999yyy888zzz777www666vvv555uuu";
        client.sendFirmwareStatus("DownloadFailed", tamperedHash);
        checkStopRequested();

        // Start transaction (but it should be flagged as suspicious)
        client.sendTransactionStart(currentTransactionId);

        // Only 2 meter updates
        for (int i = 0; i < 2; i++) {
            Thread.sleep(meterUpdateIntervalMs);
            checkStopRequested();
            currentMeterValue += profile.getEnergyPerUpdateKwh();
            client.sendMeterUpdate(currentTransactionId, currentMeterValue);
        }

        checkStopRequested();
        client.sendTransactionEnd(currentTransactionId, currentMeterValue);
    }

    /**
     * Scenario 3: Rapid Reconnect / Boot Loop.
     */
    @Scheduled(initialDelayString = "180000", fixedDelayString = "120000")
    public void scenario_reconnectLoop() throws Exception {
        if (!autoScenariosEnabled) {
            return;
        }
        if (chargingActive) {
            log.debug("Charge session already active, skipping");
            return;
        }
        chargingActive = true;
        currentScenarioName = "reconnectLoop";
        try {
            runReconnectLoopScenario();
        } finally {
            chargingActive = false;
            currentScenarioName = null;
            clearCurrentTransactionId();
        }
    }

    private void runReconnectLoopScenario() throws Exception {
        log.info("🚗 SCENARIO: Rapid Reconnect Loop");
        checkStopRequested();

        // Disconnect and reconnect 3 times rapidly
        for (int i = 0; i < 3; i++) {
            checkStopRequested();
            log.warn("Reconnect attempt {}/3", i + 1);
            try {
                client.disconnect();
                Thread.sleep(2000);
                checkStopRequested();
                client.connect();
                Thread.sleep(3000);
                checkStopRequested();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.error("Reconnect attempt {} failed", i + 1, e);
            }
        }
    }

    private void runIsoPnCHappyPathScenario() throws Exception {
        log.info("🚗 SCENARIO: ISO-15118 Plug and Charge happy path");
        EvTelemetryProfileProperties.TelemetryProfile profile = telemetryProfiles.resolveActiveProfile();
        long meterUpdateIntervalMs = Math.max(1000, profile.getMeterUpdateIntervalMs());
        checkStopRequested();

        currentTransactionId = "TXN-ISO-PNC-" + UUID.randomUUID();
        currentMeterValue = 0.0;

        client.sendFirmwareStatus("Downloaded", "abc123def456ghi789jkl012mno345pqr");
        checkStopRequested();
        client.sendTransactionStart(currentTransactionId, "PlugAndCharge", "ISO15118-CERT-LOCAL-001");

        for (int i = 0; i < 3; i++) {
            Thread.sleep(meterUpdateIntervalMs);
            checkStopRequested();
            currentMeterValue += profile.getEnergyPerUpdateKwh();
            client.sendMeterUpdate(currentTransactionId, currentMeterValue);
        }

        checkStopRequested();
        client.sendTransactionEnd(currentTransactionId, currentMeterValue);
    }

    private void runIsoPnCCertMissingScenario() throws Exception {
        log.info("🚗 SCENARIO: ISO-15118 Plug and Charge misconfiguration (missing contract certificate)");
        EvTelemetryProfileProperties.TelemetryProfile profile = telemetryProfiles.resolveActiveProfile();
        long meterUpdateIntervalMs = Math.max(1000, profile.getMeterUpdateIntervalMs());
        checkStopRequested();

        currentTransactionId = "TXN-ISO-MISS-" + UUID.randomUUID();
        currentMeterValue = 0.0;

        client.sendTransactionStart(currentTransactionId, "PlugAndCharge", "");

        for (int i = 0; i < 2; i++) {
            Thread.sleep(meterUpdateIntervalMs);
            checkStopRequested();
            currentMeterValue += profile.getEnergyPerUpdateKwh();
            client.sendMeterUpdate(currentTransactionId, currentMeterValue);
        }

        checkStopRequested();
        client.sendTransactionEnd(currentTransactionId, currentMeterValue);
    }

    private static final class ScenarioStoppedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
