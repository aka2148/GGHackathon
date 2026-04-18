package com.cybersecuals.gridgarrison.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ev.simulator.verify")
public class EvVerificationProperties {

    private boolean enabled = true;
    private String backendBaseUrl = "http://localhost:8443";
    private String goldenHashPath = "/trust/api/golden-hash";
    private String verifyPath = "/trust/api/verify-firmware";
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;
    private String reportedHash = "abc123def456ghi789jkl012mno345pqr";
    private String firmwareVersion = "1.0.0";
    private Retry retry = new Retry();

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

    public String getGoldenHashPath() {
        return goldenHashPath;
    }

    public void setGoldenHashPath(String goldenHashPath) {
        this.goldenHashPath = goldenHashPath;
    }

    public String getVerifyPath() {
        return verifyPath;
    }

    public void setVerifyPath(String verifyPath) {
        this.verifyPath = verifyPath;
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

    public String getReportedHash() {
        return reportedHash;
    }

    public void setReportedHash(String reportedHash) {
        this.reportedHash = reportedHash;
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
}
