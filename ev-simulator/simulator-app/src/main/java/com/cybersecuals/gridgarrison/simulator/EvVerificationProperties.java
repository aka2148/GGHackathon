package com.cybersecuals.gridgarrison.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ev.simulator.verify")
public class EvVerificationProperties {

    private boolean enabled = true;
    private String backendBaseUrl = "http://localhost:8443";
    private String componentHashPath = "/visualizer/api/generate-hash";
    private String latestVerdictPath = "/trust/api/latest-verdict";
    private String escrowIntentPath = "/trust/api/escrow/intent";
    private String escrowActivePath = "/trust/api/escrow/active";
    private String escrowResetPath = "/trust/api/escrow/reset";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;
    private String firmwareStatus = "Downloaded";
    private String firmwareVersion = "1.0.0";
    private Retry retry = new Retry();
    private VerdictPoll verdictPoll = new VerdictPoll();
    private EscrowPoll escrowPoll = new EscrowPoll();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackendBaseUrl() {
        return backendBaseUrl;
    }

    public void setBackendBaseUrl(String backendBaseUrl) {
        this.backendBaseUrl = backendBaseUrl;
    }

    public String getComponentHashPath() {
        return componentHashPath;
    }

    public void setComponentHashPath(String componentHashPath) {
        this.componentHashPath = componentHashPath;
    }

    public String getLatestVerdictPath() {
        return latestVerdictPath;
    }

    public void setLatestVerdictPath(String latestVerdictPath) {
        this.latestVerdictPath = latestVerdictPath;
    }

    public String getEscrowIntentPath() {
        return escrowIntentPath;
    }

    public void setEscrowIntentPath(String escrowIntentPath) {
        this.escrowIntentPath = escrowIntentPath;
    }

    public String getEscrowActivePath() {
        return escrowActivePath;
    }

    public void setEscrowActivePath(String escrowActivePath) {
        this.escrowActivePath = escrowActivePath;
    }

    public String getEscrowResetPath() {
        return escrowResetPath;
    }

    public void setEscrowResetPath(String escrowResetPath) {
        this.escrowResetPath = escrowResetPath;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getFirmwareStatus() {
        return firmwareStatus;
    }

    public void setFirmwareStatus(String firmwareStatus) {
        this.firmwareStatus = firmwareStatus;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public VerdictPoll getVerdictPoll() {
        return verdictPoll;
    }

    public void setVerdictPoll(VerdictPoll verdictPoll) {
        this.verdictPoll = verdictPoll;
    }

    public EscrowPoll getEscrowPoll() {
        return escrowPoll;
    }

    public void setEscrowPoll(EscrowPoll escrowPoll) {
        this.escrowPoll = escrowPoll;
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 5000;
        private double multiplier = 2.0;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    public static class VerdictPoll {
        private int maxAttempts = 12;
        private long intervalMs = 500;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class EscrowPoll {
        private int maxAttempts = 16;
        private long intervalMs = 500;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }
}
