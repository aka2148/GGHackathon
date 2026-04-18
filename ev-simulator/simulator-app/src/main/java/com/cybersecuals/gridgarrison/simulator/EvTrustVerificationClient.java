package com.cybersecuals.gridgarrison.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class EvTrustVerificationClient {

    private final RestTemplateBuilder restTemplateBuilder;
    private final EvVerificationProperties verificationProperties;
    private final EvVerificationGateState gateState;
    private final AtomicBoolean verificationInProgress = new AtomicBoolean(false);

    private RestTemplate restTemplate;

    public EvTrustVerificationClient(RestTemplateBuilder restTemplateBuilder,
                                     EvVerificationProperties verificationProperties,
                                     EvVerificationGateState gateState) {
        this.restTemplateBuilder = restTemplateBuilder;
        this.verificationProperties = verificationProperties;
        this.gateState = gateState;
    }

    @PostConstruct
    void init() {
        restTemplate = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(Math.max(100, verificationProperties.getConnectTimeoutMs())))
            .setReadTimeout(Duration.ofMillis(Math.max(100, verificationProperties.getReadTimeoutMs())))
            .build();
    }

    public void verifyAfterConnectAsync(String stationId, String reportedHashOverride) {
        if (!verificationInProgress.compareAndSet(false, true)) {
            log.debug("Verification already in progress, skipping duplicate trigger");
            return;
        }

        String resolvedReportedHash = resolveReportedHash(reportedHashOverride);
        String resolvedFirmwareVersion = resolveFirmwareVersion(null);

        Thread verificationThread = new Thread(() -> {
            try {
                verifyWithRetry(stationId, resolvedReportedHash, resolvedFirmwareVersion);
            } finally {
                verificationInProgress.set(false);
            }
        }, "ev-verify-thread");
        verificationThread.setDaemon(true);
        verificationThread.start();
    }

    public EvVerificationGateState.Snapshot requestHashAndVerify(String stationId,
                                                                 String reportedHashOverride,
                                                                 String firmwareVersionOverride) {
        if (!verificationInProgress.compareAndSet(false, true)) {
            gateState.markPending(
                stationId,
                resolveReportedHash(reportedHashOverride),
                "Verification is already running."
            );
            return gateState.snapshot();
        }

        String resolvedReportedHash = resolveReportedHash(reportedHashOverride);
        String resolvedFirmwareVersion = resolveFirmwareVersion(firmwareVersionOverride);
        try {
            verifyWithRetry(stationId, resolvedReportedHash, resolvedFirmwareVersion);
            return gateState.snapshot();
        } finally {
            verificationInProgress.set(false);
        }
    }

    private void verifyWithRetry(String stationId, String reportedHash, String firmwareVersion) {
        if (!verificationProperties.isEnabled()) {
            gateState.markFailed(
                stationId,
                reportedHash,
                null,
                null,
                null,
                null,
                null,
                "Verification gate is disabled by configuration."
            );
            return;
        }

        if (reportedHash == null || reportedHash.isBlank()) {
            gateState.markFailed(
                stationId,
                null,
                null,
                null,
                null,
                null,
                null,
                "No reported hash available. Send firmware status first or provide a hash."
            );
            return;
        }

        EvVerificationProperties.Retry retry = verificationProperties.getRetry();
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        long delayMs = Math.max(100L, retry.getInitialDelayMs());
        long maxDelayMs = Math.max(delayMs, retry.getMaxDelayMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            gateState.markPending(
                stationId,
                reportedHash,
                String.format("Running firmware verification (attempt %d/%d)", attempt, maxAttempts)
            );

            try {
                GoldenHashResponse goldenHashResponse = fetchGoldenHash(stationId);
                if (goldenHashResponse == null) {
                    throw new IllegalStateException("No golden hash response received from backend");
                }
                if (goldenHashResponse.goldenHash() == null || goldenHashResponse.goldenHash().isBlank()) {
                    String reason = goldenHashResponse.message() == null
                        ? "Golden hash is missing on backend"
                        : goldenHashResponse.message();
                    throw new IllegalStateException(reason);
                }

                VerifyFirmwareResponse verifyResponse = verifyFirmware(stationId, reportedHash, firmwareVersion);
                if (verifyResponse == null) {
                    throw new IllegalStateException("No verification response received from backend");
                }

                if (verifyResponse.verified()) {
                    gateState.markVerified(
                        verifyResponse.stationId(),
                        verifyResponse.reportedHash(),
                        verifyResponse.goldenHash(),
                        verifyResponse.verificationStatus(),
                        verifyResponse.signatureVerified(),
                        verifyResponse.contractAddress(),
                        verifyResponse.rpcStatus(),
                        verifyResponse.message()
                    );
                    log.info("Firmware verification passed for stationId={} status={}",
                        verifyResponse.stationId(), verifyResponse.verificationStatus());
                    return;
                }

                String reason = verifyResponse.message() == null
                    ? "Backend returned non-verified status"
                    : verifyResponse.message();
                throw new IllegalStateException(reason);

            } catch (Exception ex) {
                boolean lastAttempt = attempt == maxAttempts;
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("Firmware verification attempt {}/{} failed for stationId={}: {}",
                    attempt, maxAttempts, stationId, reason);

                if (lastAttempt) {
                    gateState.markFailed(
                        stationId,
                        reportedHash,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Verification failed: " + reason
                    );
                    return;
                }

                sleep(delayMs);
                long nextDelay = (long) Math.max(delayMs, delayMs * Math.max(1.0d, retry.getMultiplier()));
                delayMs = Math.min(maxDelayMs, nextDelay);
            }
        }
    }

    private String resolveReportedHash(String reportedHashOverride) {
        if (reportedHashOverride != null && !reportedHashOverride.isBlank()) {
            return reportedHashOverride.trim();
        }
        String configured = verificationProperties.getReportedHash();
        return configured == null ? null : configured.trim();
    }

    private String resolveFirmwareVersion(String firmwareVersionOverride) {
        if (firmwareVersionOverride != null && !firmwareVersionOverride.isBlank()) {
            return firmwareVersionOverride.trim();
        }
        String configured = verificationProperties.getFirmwareVersion();
        if (configured == null || configured.isBlank()) {
            return "1.0.0";
        }
        return configured.trim();
    }

    private GoldenHashResponse fetchGoldenHash(String stationId) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getGoldenHashPath()))
            .queryParam("stationId", stationId)
            .toUriString();

        return restTemplate.getForObject(url, GoldenHashResponse.class);
    }

    private VerifyFirmwareResponse verifyFirmware(String stationId,
                                                  String reportedHash,
                                                  String firmwareVersion) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getVerifyPath()))
            .queryParam("stationId", stationId)
            .queryParam("reportedHash", reportedHash)
            .queryParam("firmwareVersion", firmwareVersion)
            .toUriString();

        return restTemplate.postForObject(url, null, VerifyFirmwareResponse.class);
    }

    private String normalizeBaseUrl() {
        String configured = verificationProperties.getBackendBaseUrl();
        if (configured == null || configured.isBlank()) {
            return "http://localhost:8443";
        }
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(Math.max(50L, delayMs));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    record GoldenHashResponse(
        String stationId,
        String goldenHash,
        String manufacturerId,
        String contractAddress,
        String observedAt,
        String status,
        String message
    ) {
    }

    record VerifyFirmwareResponse(
        String stationId,
        boolean verified,
        String verificationStatus,
        String reportedHash,
        String goldenHash,
        Boolean signatureVerified,
        String contractAddress,
        String rpcStatus,
        String verificationTxHash,
        String observedAt,
        String status,
        String message
    ) {
    }
}
