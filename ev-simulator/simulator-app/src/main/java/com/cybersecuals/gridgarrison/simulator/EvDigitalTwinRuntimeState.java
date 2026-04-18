package com.cybersecuals.gridgarrison.simulator;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EvDigitalTwinRuntimeState {

    private static final ControlState DEFAULT_CONTROLS = ControlState.defaults();

    private final AtomicReference<ControlState> controls = new AtomicReference<>(DEFAULT_CONTROLS);
    private final AtomicReference<EvTrustVerificationClient.WatchdogMetricsSnapshot> lastMetrics = new AtomicReference<>();
    private final AtomicReference<EvTrustVerificationClient.WatchdogTelemetryResponse> lastTelemetry = new AtomicReference<>();
    private final AtomicReference<String> lastWarning = new AtomicReference<>();

    public ControlState controls() {
        return controls.get();
    }

    public ControlState update(Double rateMultiplier,
                               Double energyPerUpdateKwh,
                               Double powerKw,
                               Double connectorTempC,
                               Double socBiasPct,
                               Boolean telemetryForwardingEnabled) {
        ControlState current = controls.get();
        ControlState next = new ControlState(
            rateMultiplier == null ? current.rateMultiplier() : clamp(rateMultiplier, 0.1d, 5.0d),
            energyPerUpdateKwh == null ? current.energyPerUpdateKwh() : sanitizeEnergyOverride(energyPerUpdateKwh),
            powerKw == null ? current.powerKw() : clamp(powerKw, 0.0d, 350.0d),
            connectorTempC == null ? current.connectorTempC() : clamp(connectorTempC, -20.0d, 140.0d),
            socBiasPct == null ? current.socBiasPct() : clamp(socBiasPct, -30.0d, 30.0d),
            telemetryForwardingEnabled == null ? current.telemetryForwardingEnabled() : telemetryForwardingEnabled
        );
        controls.set(next);
        return next;
    }

    public ControlState reset() {
        controls.set(DEFAULT_CONTROLS);
        return DEFAULT_CONTROLS;
    }

    public double resolveMeterStepKwh(double requestedKwh, double profileDefaultKwh) {
        ControlState controlState = controls.get();
        double base = controlState.energyPerUpdateKwh() != null
            ? controlState.energyPerUpdateKwh()
            : (requestedKwh > 0.0d ? requestedKwh : Math.max(0.0d, profileDefaultKwh));
        return round3(Math.max(0.0d, base * controlState.rateMultiplier()));
    }

    public void setLastMetrics(EvTrustVerificationClient.WatchdogMetricsSnapshot metrics) {
        if (metrics != null) {
            lastMetrics.set(metrics);
        }
    }

    public void setLastTelemetry(EvTrustVerificationClient.WatchdogTelemetryResponse telemetry) {
        if (telemetry != null) {
            lastTelemetry.set(telemetry);
            if (telemetry.metrics() != null) {
                lastMetrics.set(telemetry.metrics());
            }
        }
    }

    public void setWarning(String warning) {
        lastWarning.set(warning);
    }

    public Snapshot snapshot() {
        return new Snapshot(
            controls.get(),
            lastMetrics.get(),
            lastTelemetry.get(),
            lastWarning.get(),
            Instant.now()
        );
    }

    private Double sanitizeEnergyOverride(Double value) {
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value <= 0.0d) {
            return null;
        }
        return round3(value);
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.min(max, Math.max(min, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    public record ControlState(
        double rateMultiplier,
        Double energyPerUpdateKwh,
        double powerKw,
        double connectorTempC,
        double socBiasPct,
        boolean telemetryForwardingEnabled
    ) {
        static ControlState defaults() {
            return new ControlState(1.0d, null, 46.0d, 31.0d, 0.0d, true);
        }
    }

    public record Snapshot(
        ControlState controls,
        EvTrustVerificationClient.WatchdogMetricsSnapshot metrics,
        EvTrustVerificationClient.WatchdogTelemetryResponse telemetry,
        String warning,
        Instant capturedAt
    ) {
    }
}
