package com.cybersecuals.gridgarrison.watchdog.service;

import com.cybersecuals.gridgarrison.shared.dto.ChargingSession;
import com.cybersecuals.gridgarrison.shared.dto.TelemetrySample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Digital Twin with real-time anomaly detection.
 *
 * ── What was broken (and fixed) ────────────────────────────────────────────
 *
 * PROBLEM 1 — ApplicationEventPublisher was never injected.
 *   detectAnomalies() found anomalies, built AnomalyReport objects, logged them,
 *   but NEVER published an AnomalyEvent. EscrowAnomalyListener.onAnomaly() was
 *   therefore never called, so no refund was ever sent to Ganache.
 *   FIX: Inject ApplicationEventPublisher via constructor (@RequiredArgsConstructor).
 *        Call eventPublisher.publishEvent(anomalyEvent) inside every rule that fires.
 *
 * PROBLEM 2 — No telemetry ingestion path.
 *   Power and temperature readings from the demo had nowhere to go. The twin only
 *   tracked whole ChargingSession objects (energy totals), not sample-level readings.
 *   FIX: Added ingestTelemetry(TelemetrySample) method with four real-time detectors:
 *     CAT-1  Power spike:   absolute threshold + rate-of-change + twin mismatch
 *     CAT-2  Temp spike:    absolute threshold + rate-of-change
 *     CAT-3  SoC spoof:     impossible jump + rate vs power consistency
 *     CAT-4  Firmware mismatch (checked here too in case mid-session re-announce)
 *
 * PROBLEM 3 — Severity was never computed — every anomaly would silently be LOW.
 *   FIX: computeSeverity() counts how many distinct signal categories fired.
 *     1 soft signal = LOW, 1 hard threshold = MEDIUM, 2 categories = HIGH,
 *     3+ or firmware = CRITICAL. Only HIGH/CRITICAL trigger escrow refund.
 *
 * PROBLEM 4 — quarantineStation() set TwinStatus.QUARANTINED but the interface
 *   declared it — the original impl never told the event publisher about it.
 *   FIX: quarantineStation() now also publishes a CRITICAL AnomalyEvent so the
 *   escrow refund fires even when quarantine is triggered from outside.
 * ────────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
@RequiredArgsConstructor
class DigitalTwinServiceImpl implements DigitalTwinService {

    // ── Thresholds ────────────────────────────────────────────────────────────

    private static final int    SESSION_RING_SIZE         = 50;
    private static final double ENERGY_SPIKE_FACTOR       = 3.0;
    private static final int    RAPID_RECONNECT_THRESHOLD = 3;
    private static final Duration RAPID_RECONNECT_WINDOW  = Duration.ofMinutes(10);
    private static final Duration SHORT_SESSION_DURATION  = Duration.ofMinutes(2);

    // CAT-1 Power
    private static final double POWER_MAX_KW          = 150.0;
    private static final double POWER_RATE_MAX_KW     = 50.0;
    private static final double POWER_TWIN_FACTOR     = 2.5;
    private static final double POWER_BASELINE_MIN    = 5.0;

    // CAT-2 Temperature
    private static final double TEMP_MAX_C            = 65.0;
    private static final double TEMP_RATE_MAX_C       = 5.0;

    // CAT-3 SoC spoof
    private static final double SOC_MAX_JUMP_PCT      = 15.0;
    private static final double SOC_KWH_PER_PCT       = 0.5;

    // Ideal-vs-actual drift checks
    private static final double TWIN_DRIFT_MIN_ENERGY_KWH = 0.2;

    // Baseline EMA smoothing
    private static final double EMA_ALPHA             = 0.15;

    @Value("${gridgarrison.watchdog.heartbeat-timeout-ms:180000}")
    private long heartbeatTimeoutMs;

    @Value("${gridgarrison.watchdog.twin-drift-warn-pct:15.0}")
    private double twinDriftWarnPct;

    @Value("${gridgarrison.watchdog.twin-drift-high-pct:30.0}")
    private double twinDriftHighPct;

    // ── Dependencies ──────────────────────────────────────────────────────────

    /**
     * THE CRITICAL FIX: This was completely missing from the original impl.
     * Without it, anomalies were detected but AnomalyEvent was never published,
     * so EscrowAnomalyListener never ran, so Ganache never received a refund.
     */
    private final ApplicationEventPublisher eventPublisher;

    // stationId → mutable twin state
    private final Map<String, MutableTwin> twins = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public void registerStation(String stationId) {
        twins.computeIfAbsent(stationId, id -> {
            log.info("[Watchdog] Registering digital twin — stationId={}", id);
            return new MutableTwin(id, Instant.now());
        });
    }

    @Override
    public void updateSessionState(ChargingSession session) {
        MutableTwin twin = twins.computeIfAbsent(
            session.getStationId(), id -> new MutableTwin(id, Instant.now()));
        twin.addSession(session);
        twin.lastHeartbeat = Instant.now();
        log.debug("[Watchdog] Session updated — stationId={} state={}",
            session.getStationId(), session.getState());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-time telemetry ingestion — called on every MeterValues sample
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public List<AnomalyReport> ingestTelemetry(TelemetrySample sample) {
        return ingestTelemetry(sample, null);
    }

    @Override
    public List<AnomalyReport> ingestTelemetry(TelemetrySample sample,
                                               DigitalTwinService.TelemetryExpectation expectation) {
        MutableTwin twin = twins.computeIfAbsent(
            sample.getStationId(), id -> new MutableTwin(id, Instant.now()));

        twin.lastHeartbeat = Instant.now();

        List<AnomalyReport> flagged = new ArrayList<>();

        // ── CAT-1: Power checks ───────────────────────────────────────────────
        if (sample.getPowerKw() != null) {
            double power = sample.getPowerKw();

            // 1a Absolute threshold
            if (power > POWER_MAX_KW) {
                flagged.add(build(sample.getStationId(),
                    AnomalyReport.AnomalyType.POWER_SPIKE_ABSOLUTE,
                    String.format("Power %.1f kW exceeds hard cap %.1f kW", power, POWER_MAX_KW),
                    power, POWER_MAX_KW));
            }

            // 1b Rate-of-change (only if we have a previous reading)
            if (twin.lastPowerKw > 0) {
                double delta = Math.abs(power - twin.lastPowerKw);
                if (delta > POWER_RATE_MAX_KW) {
                    flagged.add(build(sample.getStationId(),
                        AnomalyReport.AnomalyType.POWER_SPIKE_RATE,
                        String.format("Power jumped %.1f kW in one sample (max %.1f kW/sample)",
                            delta, POWER_RATE_MAX_KW),
                        delta, POWER_RATE_MAX_KW));
                }
            }

            // 1c Twin mismatch — live vs learned baseline
            if (twin.baselinePowerKw > POWER_BASELINE_MIN
                    && power > twin.baselinePowerKw * POWER_TWIN_FACTOR) {
                flagged.add(build(sample.getStationId(),
                    AnomalyReport.AnomalyType.POWER_TWIN_MISMATCH,
                    String.format("Live power %.1f kW is %.1fx above twin baseline %.1f kW",
                        power, power / twin.baselinePowerKw, twin.baselinePowerKw),
                    power, twin.baselinePowerKw));
            }

            // Update readings + EMA baseline only on clean samples
            twin.lastPowerKw = power;
            if (flagged.isEmpty()) twin.baselinePowerKw = ema(twin.baselinePowerKw, power);
        }

        // ── CAT-2: Temperature checks ─────────────────────────────────────────
        if (sample.getConnectorTempC() != null) {
            double temp = sample.getConnectorTempC();

            // 2a Absolute threshold
            if (temp > TEMP_MAX_C) {
                flagged.add(build(sample.getStationId(),
                    AnomalyReport.AnomalyType.TEMPERATURE_SPIKE,
                    String.format("Connector temp %.1f °C exceeds cap %.1f °C", temp, TEMP_MAX_C),
                    temp, TEMP_MAX_C));
            }

            // 2b Rate-of-change
            if (twin.lastTempC > 0) {
                double delta = temp - twin.lastTempC; // rising only
                if (delta > TEMP_RATE_MAX_C) {
                    flagged.add(build(sample.getStationId(),
                        AnomalyReport.AnomalyType.TEMPERATURE_RATE,
                        String.format("Temp rose %.1f °C in one sample (max %.1f °C/sample)",
                            delta, TEMP_RATE_MAX_C),
                        delta, TEMP_RATE_MAX_C));
                }
            }

            twin.lastTempC = temp;
            boolean hasThisTemp = flagged.stream().anyMatch(r ->
                r.getType() == AnomalyReport.AnomalyType.TEMPERATURE_SPIKE ||
                r.getType() == AnomalyReport.AnomalyType.TEMPERATURE_RATE);
            if (!hasThisTemp) twin.baselineTempC = ema(twin.baselineTempC, temp);
        }

        // ── CAT-3: SoC spoof checks ───────────────────────────────────────────
        if (sample.getSocPercent() != null) {
            double soc = sample.getSocPercent();

            // 3a Impossible jump
            if (twin.lastSocPercent > 0) {
                double jump = Math.abs(soc - twin.lastSocPercent);
                if (jump > SOC_MAX_JUMP_PCT) {
                    flagged.add(build(sample.getStationId(),
                        AnomalyReport.AnomalyType.SOC_SPOOF,
                        String.format("SoC changed %.1f%% in one sample (max %.1f%%) — possible spoof",
                            jump, SOC_MAX_JUMP_PCT),
                        jump, SOC_MAX_JUMP_PCT));
                }
            }

            // 3b SoC rate vs power (SoC rising but power near zero → spoofed)
            if (twin.lastSocPercent > 0 && sample.getPowerKw() != null) {
                double socIncrease = soc - twin.lastSocPercent;
                double power = sample.getPowerKw();
                if (socIncrease > 1.0 && power < socIncrease * SOC_KWH_PER_PCT) {
                    flagged.add(build(sample.getStationId(),
                        AnomalyReport.AnomalyType.SOC_RATE_MISMATCH,
                        String.format("SoC increased %.1f%% but power=%.1f kW is too low — signal may be spoofed",
                            socIncrease, power),
                        socIncrease, power * SOC_KWH_PER_PCT));
                }
            }

            twin.lastSocPercent = soc;
        }

        // ── CAT-4: Mid-session firmware re-announce check ─────────────────────
        if (sample.getReportedFirmwareHash() != null && twin.goldenHash != null) {
            if (!normalise(sample.getReportedFirmwareHash())
                    .equalsIgnoreCase(normalise(twin.goldenHash))) {
                flagged.add(build(sample.getStationId(),
                    AnomalyReport.AnomalyType.FIRMWARE_MISMATCH,
                    String.format("Mid-session firmware hash changed: reported=%s golden=%s",
                        shorten(sample.getReportedFirmwareHash()), shorten(twin.goldenHash)),
                    1, 0));
            }
        }

        // Ideal-vs-actual drift against user-session simulation baseline.
        detectTwinDrift(sample, expectation, flagged);

        // ── Publish events for everything that fired ──────────────────────────
        if (!flagged.isEmpty()) {
            publishAndRecord(twin, sample.getSessionId(), flagged);
        }

        return Collections.unmodifiableList(flagged);
    }

    private void detectTwinDrift(TelemetrySample sample,
                                 DigitalTwinService.TelemetryExpectation expectation,
                                 List<AnomalyReport> flagged) {
        if (expectation == null) {
            return;
        }

        double warnThreshold = clampPercent(expectation.driftThresholdPct(), twinDriftWarnPct);
        double highThreshold = Math.max(warnThreshold, twinDriftHighPct);

        Double expectedEnergy = sanitizeExpected(expectation.expectedEnergyDeliveredKwh());
        if (sample.getEnergyDeliveredKwh() != null
            && expectedEnergy != null
            && expectedEnergy >= TWIN_DRIFT_MIN_ENERGY_KWH) {
            double observedEnergy = Math.max(0.0d, sample.getEnergyDeliveredKwh());
            double driftPct = percentDelta(observedEnergy, expectedEnergy);
            if (driftPct >= warnThreshold) {
                flagged.add(build(
                    sample.getStationId(),
                    AnomalyReport.AnomalyType.TWIN_DRIFT_ENERGY,
                    String.format(
                        "Energy drift %.1f%% at elapsed %.0f%% (observed %.3f kWh vs ideal %.3f kWh)",
                        driftPct,
                        safeElapsedPct(expectation.elapsedRatio()),
                        observedEnergy,
                        expectedEnergy
                    ),
                    observedEnergy,
                    expectedEnergy
                ));
            }
        }

        Double expectedPower = sanitizeExpected(expectation.expectedPowerKw());
        if (sample.getPowerKw() != null
            && expectedPower != null
            && expectedPower > 1.0d) {
            double observedPower = Math.max(0.0d, sample.getPowerKw());
            double driftPct = percentDelta(observedPower, expectedPower);
            if (driftPct >= warnThreshold) {
                flagged.add(build(
                    sample.getStationId(),
                    AnomalyReport.AnomalyType.TWIN_DRIFT_POWER,
                    String.format(
                        "Power drift %.1f%% at elapsed %.0f%% (observed %.1f kW vs ideal %.1f kW)",
                        driftPct,
                        safeElapsedPct(expectation.elapsedRatio()),
                        observedPower,
                        expectedPower
                    ),
                    observedPower,
                    expectedPower
                ));
            }
        }

        // Force escalation path for severe drift so the refund path is deterministic in demo.
        boolean severeTwinDrift = flagged.stream().anyMatch(report ->
            isTwinDriftType(report.getType())
                && percentDelta(report.getObservedValue(), report.getExpectedValue()) >= highThreshold
        );
        if (!severeTwinDrift) {
            return;
        }

        boolean hasEnergy = flagged.stream().anyMatch(report ->
            report.getType() == AnomalyReport.AnomalyType.TWIN_DRIFT_ENERGY
        );
        boolean hasPower = flagged.stream().anyMatch(report ->
            report.getType() == AnomalyReport.AnomalyType.TWIN_DRIFT_POWER
        );

        if (hasEnergy && !hasPower && expectedPower != null && sample.getPowerKw() != null) {
            flagged.add(build(
                sample.getStationId(),
                AnomalyReport.AnomalyType.TWIN_DRIFT_POWER,
                String.format(
                    "Severe drift escalation: power snapshot %.1f kW vs ideal %.1f kW",
                    sample.getPowerKw(),
                    expectedPower
                ),
                Math.max(0.0d, sample.getPowerKw()),
                expectedPower
            ));
        } else if (hasPower && !hasEnergy && expectedEnergy != null && sample.getEnergyDeliveredKwh() != null) {
            flagged.add(build(
                sample.getStationId(),
                AnomalyReport.AnomalyType.TWIN_DRIFT_ENERGY,
                String.format(
                    "Severe drift escalation: energy snapshot %.3f kWh vs ideal %.3f kWh",
                    sample.getEnergyDeliveredKwh(),
                    expectedEnergy
                ),
                Math.max(0.0d, sample.getEnergyDeliveredKwh()),
                expectedEnergy
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session-level anomaly sweep (used by @Scheduled and DemoController)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<AnomalyReport> detectAnomalies(String stationId) {
        MutableTwin twin = twins.get(stationId);
        if (twin == null) return Optional.empty();

        List<ChargingSession> sessions = twin.getSessions();

        // Rule 1: Energy spike
        if (!sessions.isEmpty()) {
            ChargingSession latest  = sessions.get(sessions.size() - 1);
            double          baseline = twin.getAvgEnergy();
            if (baseline > 0 && latest.getEnergyDeliveredKwh() > baseline * ENERGY_SPIKE_FACTOR) {
                AnomalyReport r = build(stationId, AnomalyReport.AnomalyType.ENERGY_SPIKE,
                    String.format("Session %.1f kWh is %.1fx above baseline %.1f kWh",
                        latest.getEnergyDeliveredKwh(),
                        latest.getEnergyDeliveredKwh() / baseline, baseline),
                    latest.getEnergyDeliveredKwh(), baseline);
                publishAndRecord(twin, null, List.of(r));
                return Optional.of(r);
            }
        }

        // Rule 2: Rapid reconnect
        long rapidCount = sessions.stream()
            .filter(s -> s.getEndedAt() != null && s.getStartedAt() != null)
            .filter(s -> s.getEndedAt().isAfter(Instant.now().minus(RAPID_RECONNECT_WINDOW)))
            .filter(s -> Duration.between(s.getStartedAt(), s.getEndedAt())
                .compareTo(SHORT_SESSION_DURATION) <= 0)
            .count();
        if (rapidCount >= RAPID_RECONNECT_THRESHOLD) {
            AnomalyReport r = build(stationId, AnomalyReport.AnomalyType.RAPID_RECONNECT,
                rapidCount + " short sessions in 10-min window", rapidCount, RAPID_RECONNECT_THRESHOLD - 1);
            publishAndRecord(twin, null, List.of(r));
            return Optional.of(r);
        }

        // Rule 3: Heartbeat timeout
        long silenceMs = Duration.between(twin.lastHeartbeat, Instant.now()).toMillis();
        if (silenceMs > heartbeatTimeoutMs) {
            AnomalyReport r = build(stationId, AnomalyReport.AnomalyType.HEARTBEAT_MISSED,
                "Station silent for " + silenceMs + "ms", silenceMs, heartbeatTimeoutMs);
            publishAndRecord(twin, null, List.of(r));
            return Optional.of(r);
        }

        return Optional.empty();
    }

    @Override
    public Optional<StationTwin> getTwin(String stationId) {
        return Optional.ofNullable(twins.get(stationId)).map(MutableTwin::toSnapshot);
    }

    @Override
    public Optional<StationTwinDiagnostics> getDiagnostics(String stationId) {
        return Optional.ofNullable(twins.get(stationId)).map(MutableTwin::toDiagnostics);
    }

    @Override
    public void quarantineStation(String stationId, String reason) {
        MutableTwin twin = twins.get(stationId);
        if (twin == null) {
            log.warn("[Watchdog] Cannot quarantine unknown station={}", stationId);
            return;
        }
        twin.quarantine(reason);

        // Publish a CRITICAL event so EscrowAnomalyListener issues the refund
        AnomalyEvent event = new AnomalyEvent(
            stationId, null,
            AnomalyReport.AnomalyType.FIRMWARE_MISMATCH,
            "Station quarantined: " + reason,
            1, 0,
            AnomalyEvent.Severity.CRITICAL
        );
        eventPublisher.publishEvent(event);
        log.error("[Watchdog] Station QUARANTINED and CRITICAL event published — stationId={}", stationId);
    }

    @Override
    public void clearQuarantine(String stationId) {
        MutableTwin twin = twins.get(stationId);
        if (twin == null) return;
        twin.status       = StationTwin.TwinStatus.HEALTHY;
        twin.anomalyCount = 0;
        twin.powerAnomalyCount = 0;
        twin.temperatureAnomalyCount = 0;
        twin.socAnomalyCount = 0;
        twin.firmwareMismatchCount = 0;
        twin.stationHashChanged = false;
        twin.powerSurgeDetected = false;
        twin.lastSeverity = null;
        twin.lastAnomalyTypes = List.of();
        twin.lastAnomalyAt = null;
        log.info("[Watchdog] Quarantine cleared — stationId={}", stationId);
    }

    /** Store the golden hash from the trust module for mid-session comparison. */
    @Override
    public void setGoldenHash(String stationId, String goldenHash) {
        MutableTwin twin = twins.computeIfAbsent(
            stationId, id -> new MutableTwin(id, Instant.now()));
        twin.goldenHash = goldenHash;
        twin.stationHashChanged = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduled sweep
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${gridgarrison.watchdog.sweep-interval-ms:60000}")
    void scheduledAnomalySweep() {
        log.debug("[Watchdog] Scheduled sweep — stations={}", twins.size());
        twins.keySet().forEach(id ->
            detectAnomalies(id).ifPresent(r ->
                log.warn("[Watchdog] Sweep anomaly — stationId={} type={}", r.getStationId(), r.getType())
            )
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Private — severity scoring and event publishing
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * THE KEY METHOD that was completely missing.
     *
     * Takes all anomaly reports from one sample/sweep, computes severity based
     * on how many distinct signal categories fired, updates twin counters, and
     * publishes AnomalyEvent so EscrowAnomalyListener can trigger the refund.
     */
    private void publishAndRecord(MutableTwin twin,
                                   String sessionId,
                                   List<AnomalyReport> flagged) {

        // Count distinct categories that fired
        Set<String> categories = new HashSet<>();
        boolean hasFirmware = false;
        boolean hasSevereTwinDrift = false;
        for (AnomalyReport r : flagged) {
            categories.add(category(r.getType()));
            if (r.getType() == AnomalyReport.AnomalyType.FIRMWARE_MISMATCH) {
                hasFirmware = true;
            }
            if (isTwinDriftType(r.getType())
                && percentDelta(r.getObservedValue(), r.getExpectedValue()) >= twinDriftHighPct) {
                hasSevereTwinDrift = true;
            }
        }

        int catCount = categories.size();

        // Compute severity
        AnomalyEvent.Severity severity;
        if (hasFirmware || catCount >= 3) {
            severity = AnomalyEvent.Severity.CRITICAL;   // triggers refund + quarantine
        } else if (hasSevereTwinDrift || catCount >= 2) {
            severity = AnomalyEvent.Severity.HIGH;        // triggers refund
        } else {
            AnomalyReport first = flagged.get(0);
            severity = isHardThreshold(first.getType())
                ? AnomalyEvent.Severity.MEDIUM             // warn only
                : AnomalyEvent.Severity.LOW;               // log only
        }

        // Update twin state
        twin.anomalyCount += flagged.size();
        updateAnomalyCounters(twin, flagged);
        twin.lastSeverity = severity;
        twin.lastAnomalyTypes = flagged.stream()
            .map(r -> r.getType().name())
            .distinct()
            .toList();
        twin.lastAnomalyAt = Instant.now();

        if (severity == AnomalyEvent.Severity.CRITICAL) {
            twin.status = StationTwin.TwinStatus.QUARANTINED;
        } else if (severity == AnomalyEvent.Severity.HIGH
                || severity == AnomalyEvent.Severity.MEDIUM) {
            twin.status = StationTwin.TwinStatus.SUSPICIOUS;
        }

        // Build description
        AnomalyReport primary = flagged.get(0);
        String desc = flagged.size() == 1
            ? primary.getDescription()
            : catCount + " categories: " +
              flagged.stream().map(r -> r.getType().name())
                  .distinct().reduce((a, b) -> a + ", " + b).orElse("");

        log.warn("[Watchdog] ANOMALY stationId={} severity={} types={} desc={}",
            twin.stationId, severity, desc, desc);

        // PUBLISH — this is the line that makes EscrowAnomalyListener fire
        eventPublisher.publishEvent(new AnomalyEvent(
            twin.stationId,
            sessionId,
            primary.getType(),
            primary.getDescription(),
            primary.getObservedValue(),
            primary.getExpectedValue(),
            severity
        ));
    }

    private void updateAnomalyCounters(MutableTwin twin, List<AnomalyReport> flagged) {
        for (AnomalyReport report : flagged) {
            switch (report.getType()) {
                case POWER_SPIKE_ABSOLUTE, POWER_SPIKE_RATE, POWER_TWIN_MISMATCH -> {
                    twin.powerAnomalyCount++;
                    twin.powerSurgeDetected = true;
                }
                case TEMPERATURE_SPIKE, TEMPERATURE_RATE -> twin.temperatureAnomalyCount++;
                case SOC_SPOOF, SOC_RATE_MISMATCH -> twin.socAnomalyCount++;
                case TWIN_DRIFT_ENERGY, TWIN_DRIFT_POWER -> twin.powerAnomalyCount++;
                case FIRMWARE_MISMATCH -> {
                    twin.firmwareMismatchCount++;
                    twin.stationHashChanged = true;
                }
                default -> {
                    // Session-level anomaly types are represented by total anomaly count.
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═════════════════════════════════════════════════════════════════════════

    private AnomalyReport build(String stationId, AnomalyReport.AnomalyType type,
                                 String description, double observed, double expected) {
        return AnomalyReport.builder()
            .stationId(stationId)
            .type(type)
            .description(description)
            .observedValue(observed)
            .expectedValue(expected)
            .detectedAt(Instant.now())
            .build();
    }

    private double ema(double current, double newValue) {
        if (current <= 0) return newValue;
        return current * (1 - EMA_ALPHA) + newValue * EMA_ALPHA;
    }

    private String category(AnomalyReport.AnomalyType type) {
        return switch (type) {
            case ENERGY_SPIKE, POWER_SPIKE_ABSOLUTE,
                 POWER_SPIKE_RATE, POWER_TWIN_MISMATCH -> "POWER";
            case TEMPERATURE_SPIKE, TEMPERATURE_RATE   -> "THERMAL";
            case SOC_SPOOF, SOC_RATE_MISMATCH           -> "SOC";
            case TWIN_DRIFT_ENERGY                      -> "TWIN_ENERGY";
            case TWIN_DRIFT_POWER                       -> "TWIN_POWER";
            case FIRMWARE_MISMATCH                      -> "IDENTITY";
            default                                      -> "OTHER";
        };
    }

    private boolean isHardThreshold(AnomalyReport.AnomalyType type) {
        return switch (type) {
            case POWER_SPIKE_ABSOLUTE, TEMPERATURE_SPIKE,
                 SOC_SPOOF, FIRMWARE_MISMATCH,
                 TWIN_DRIFT_ENERGY, TWIN_DRIFT_POWER -> true;
            default -> false;
        };
    }

    private boolean isTwinDriftType(AnomalyReport.AnomalyType type) {
        return type == AnomalyReport.AnomalyType.TWIN_DRIFT_ENERGY
            || type == AnomalyReport.AnomalyType.TWIN_DRIFT_POWER;
    }

    private double percentDelta(double observed, double expected) {
        double base = Math.max(0.001d, Math.abs(expected));
        return Math.abs(observed - expected) * 100.0d / base;
    }

    private double clampPercent(Double value, double fallback) {
        if (value == null || !Double.isFinite(value) || value <= 0.0d) {
            return fallback;
        }
        return Math.min(500.0d, Math.max(1.0d, value));
    }

    private Double sanitizeExpected(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0d) {
            return null;
        }
        return value;
    }

    private double safeElapsedPct(Double elapsedRatio) {
        if (elapsedRatio == null || !Double.isFinite(elapsedRatio)) {
            return 0.0d;
        }
        return Math.min(100.0d, Math.max(0.0d, elapsedRatio * 100.0d));
    }

    private String normalise(String hash) {
        if (hash == null) return "";
        return hash.startsWith("0x") ? hash.substring(2).toLowerCase() : hash.toLowerCase();
    }

    private String shorten(String hash) {
        if (hash == null) return "null";
        String h = hash.startsWith("0x") ? hash : "0x" + hash;
        return h.length() > 10 ? h.substring(0, 10) + "…" : h;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internal mutable state
    // ═════════════════════════════════════════════════════════════════════════

    private static class MutableTwin {
        final String  stationId;
        final Instant registeredAt;
        Instant       lastHeartbeat;

        // Session history
        final Deque<ChargingSession> sessions = new ArrayDeque<>(SESSION_RING_SIZE);

        // Counters
        int     anomalyCount = 0;
        int     powerAnomalyCount = 0;
        int     temperatureAnomalyCount = 0;
        int     socAnomalyCount = 0;
        int     firmwareMismatchCount = 0;
        StationTwin.TwinStatus status = StationTwin.TwinStatus.HEALTHY;

        // Learned baselines (EMA)
        double  baselinePowerKw = 0;
        double  baselineTempC   = 0;

        // Latest live readings (for rate-of-change)
        double  lastPowerKw    = 0;
        double  lastTempC      = 0;
        double  lastSocPercent = 0;

        // Trust state
        String  goldenHash     = null;
        boolean stationHashChanged = false;
        boolean powerSurgeDetected = false;

        AnomalyEvent.Severity lastSeverity = null;
        List<String> lastAnomalyTypes = List.of();
        Instant lastAnomalyAt = null;

        MutableTwin(String stationId, Instant registeredAt) {
            this.stationId     = stationId;
            this.registeredAt  = registeredAt;
            this.lastHeartbeat = registeredAt;
        }

        void addSession(ChargingSession s) {
            if (sessions.size() >= SESSION_RING_SIZE) sessions.pollFirst();
            sessions.addLast(s);
        }

        List<ChargingSession> getSessions() { return new ArrayList<>(sessions); }

        double getAvgEnergy() {
            return sessions.stream()
                .mapToDouble(ChargingSession::getEnergyDeliveredKwh)
                .average().orElse(0.0);
        }

        void quarantine(String reason) {
            this.status = StationTwin.TwinStatus.QUARANTINED;
            log.warn("[Watchdog] Twin QUARANTINED — stationId={} reason={}", stationId, reason);
        }

        // Lombok @Slf4j is on the outer class, not here — use direct logger
        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MutableTwin.class);

        StationTwin toSnapshot() {
            return StationTwin.builder()
                .stationId(stationId)
                .registeredAt(registeredAt)
                .lastHeartbeat(lastHeartbeat)
                .recentSessions(getSessions())
                .avgEnergyPerSessionKwh(getAvgEnergy())
                .totalAnomaliesRaised(anomalyCount)
                .status(status)
                .build();
        }

        StationTwinDiagnostics toDiagnostics() {
            return new StationTwinDiagnostics(
                stationId,
                status,
                lastHeartbeat,
                anomalyCount,
                anomalyCount,
                powerAnomalyCount,
                temperatureAnomalyCount,
                socAnomalyCount,
                firmwareMismatchCount,
                stationHashChanged,
                powerSurgeDetected,
                lastSeverity == null ? "NONE" : lastSeverity.name(),
                lastAnomalyTypes,
                lastAnomalyAt,
                lastPowerKw <= 0.0d ? null : lastPowerKw,
                lastTempC <= 0.0d ? null : lastTempC,
                lastSocPercent <= 0.0d ? null : lastSocPercent,
                goldenHash
            );
        }
    }
}