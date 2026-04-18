package com.cybersecuals.gridgarrison.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

    public void verifyAfterConnectAsync(String stationId,
                                        String firmwareStatusOverride,
                                        String firmwareVersionOverride,
                                        FirmwareStatusSender firmwareStatusSender) {
        if (!verificationInProgress.compareAndSet(false, true)) {
            log.debug("Verification already in progress, skipping duplicate trigger");
            return;
        }

        String resolvedFirmwareStatus = resolveFirmwareStatus(firmwareStatusOverride);
        String resolvedFirmwareVersion = resolveFirmwareVersion(firmwareVersionOverride);

        Thread verificationThread = new Thread(() -> {
            try {
                verifyWithRetry(stationId, resolvedFirmwareStatus, resolvedFirmwareVersion, firmwareStatusSender);
            } finally {
                verificationInProgress.set(false);
            }
        }, "ev-verify-thread");
        verificationThread.setDaemon(true);
        verificationThread.start();
    }

    public RequestHashVerificationResult requestHashAndVerify(String stationId,
                                                              String firmwareStatusOverride,
                                                              String firmwareVersionOverride,
                                                              FirmwareStatusSender firmwareStatusSender) {
        if (!verificationInProgress.compareAndSet(false, true)) {
            gateState.markPending(
                stationId,
                null,
                "Verification is already running."
            );
            return new RequestHashVerificationResult(gateState.snapshot(), null, null, false, List.of());
        }

        String resolvedFirmwareStatus = resolveFirmwareStatus(firmwareStatusOverride);
        String resolvedFirmwareVersion = resolveFirmwareVersion(firmwareVersionOverride);
        try {
            return verifyWithRetry(stationId, resolvedFirmwareStatus, resolvedFirmwareVersion, firmwareStatusSender);
        } finally {
            verificationInProgress.set(false);
        }
    }

    private RequestHashVerificationResult verifyWithRetry(String stationId,
                                                          String firmwareStatus,
                                                          String firmwareVersion,
                                                          FirmwareStatusSender firmwareStatusSender) {
        if (!verificationProperties.isEnabled()) {
            gateState.markFailed(
                stationId,
                null,
                null,
                null,
                null,
                null,
                null,
                "Verification gate is disabled by configuration."
            );
            return new RequestHashVerificationResult(gateState.snapshot(), null, null, false, List.of());
        }

        if (firmwareStatusSender == null) {
            gateState.markFailed(
                stationId,
                null,
                null,
                null,
                null,
                null,
                null,
                "No firmware status sender available for verification flow."
            );
            return new RequestHashVerificationResult(gateState.snapshot(), null, null, false, List.of());
        }

        EvVerificationProperties.Retry retry = verificationProperties.getRetry();
        int maxAttempts = Math.max(1, retry.getMaxAttempts());
        long delayMs = Math.max(100L, retry.getInitialDelayMs());
        long maxDelayMs = Math.max(delayMs, retry.getMaxDelayMs());

        GeneratedHashResponse latestGeneratedHash = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            gateState.markPending(
                stationId,
                latestGeneratedHash == null ? null : latestGeneratedHash.hash(),
                String.format("Requesting component-state hash and trust verdict (attempt %d/%d)", attempt, maxAttempts)
            );

            try {
                latestGeneratedHash = requestComponentHash(stationId);
                if (latestGeneratedHash == null || latestGeneratedHash.hash() == null
                    || latestGeneratedHash.hash().isBlank()) {
                    throw new IllegalStateException("Backend did not return a component-state hash");
                }

                String reportedHash = latestGeneratedHash.hash();
                gateState.markPending(
                    stationId,
                    reportedHash,
                    "Firmware hash requested from component state. Sending status "
                        + firmwareStatus + " firmwareVersion=" + firmwareVersion
                        + " and awaiting trust verdict."
                );

                firmwareStatusSender.send(firmwareStatus, reportedHash, firmwareVersion);

                LatestVerdictResponse verdict = awaitLatestVerdict(stationId, reportedHash);
                if (verdict == null) {
                    throw new IllegalStateException("No trust verdict returned for the requested hash");
                }

                if (isVerifiedVerdict(verdict)) {
                    gateState.markVerified(
                        verdict.stationId(),
                        verdict.reportedHash(),
                        verdict.expectedHash(),
                        verdict.verificationStatus(),
                        verdict.signatureVerified(),
                        verdict.contractAddress(),
                        verdict.rpcStatus(),
                        verdict.message()
                    );
                    log.info("Firmware verification passed via event flow for stationId={} resultStatus={}",
                        verdict.stationId(), verdict.resultStatus());
                    return new RequestHashVerificationResult(
                        gateState.snapshot(),
                        reportedHash,
                        latestGeneratedHash.generationSource(),
                        latestGeneratedHash.anyTampered(),
                        latestGeneratedHash.tamperedComponents() == null ? List.of() : latestGeneratedHash.tamperedComponents()
                    );
                }

                if ("PENDING".equalsIgnoreCase(verdict.resultStatus())) {
                    throw new IllegalStateException("Trust verdict is still pending");
                }

                String reason = verdict.message() == null
                    ? "Backend returned non-verified status"
                    : verdict.message();
                throw new IllegalStateException(reason);

            } catch (Exception ex) {
                boolean lastAttempt = attempt == maxAttempts;
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("Firmware verification attempt {}/{} failed for stationId={}: {}",
                    attempt, maxAttempts, stationId, reason);

                if (lastAttempt) {
                    gateState.markFailed(
                        stationId,
                        latestGeneratedHash == null ? null : latestGeneratedHash.hash(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Verification failed: " + reason
                    );
                    return new RequestHashVerificationResult(
                        gateState.snapshot(),
                        latestGeneratedHash == null ? null : latestGeneratedHash.hash(),
                        latestGeneratedHash == null ? null : latestGeneratedHash.generationSource(),
                        latestGeneratedHash != null && latestGeneratedHash.anyTampered(),
                        latestGeneratedHash == null || latestGeneratedHash.tamperedComponents() == null
                            ? List.of()
                            : latestGeneratedHash.tamperedComponents()
                    );
                }

                sleep(delayMs);
                long nextDelay = (long) Math.max(delayMs, delayMs * Math.max(1.0d, retry.getMultiplier()));
                delayMs = Math.min(maxDelayMs, nextDelay);
            }
        }

        return new RequestHashVerificationResult(gateState.snapshot(), null, null, false, List.of());
    }

    private GeneratedHashResponse requestComponentHash(String stationId) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getComponentHashPath()))
            .queryParam("stationId", stationId)
            .toUriString();

        return restTemplate.postForObject(url, null, GeneratedHashResponse.class);
    }

    private LatestVerdictResponse awaitLatestVerdict(String stationId,
                                                     String expectedReportedHash) {
        EvVerificationProperties.VerdictPoll poll = verificationProperties.getVerdictPoll();
        int maxAttempts = Math.max(1, poll.getMaxAttempts());
        long intervalMs = Math.max(100L, poll.getIntervalMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LatestVerdictResponse verdict = fetchLatestVerdict(stationId);
            if (verdict == null) {
                sleep(intervalMs);
                continue;
            }

            if (!hashMatches(verdict.reportedHash(), expectedReportedHash)) {
                sleep(intervalMs);
                continue;
            }

            if (isTerminalVerdict(verdict.resultStatus())) {
                return verdict;
            }

            sleep(intervalMs);
        }

        return fetchLatestVerdict(stationId);
    }

    private LatestVerdictResponse fetchLatestVerdict(String stationId) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getLatestVerdictPath()))
            .queryParam("stationId", stationId)
            .toUriString();

        return restTemplate.getForObject(url, LatestVerdictResponse.class);
    }

    private boolean isVerifiedVerdict(LatestVerdictResponse verdict) {
        return verdict != null
            && verdict.verified()
            && "VERIFIED".equalsIgnoreCase(verdict.resultStatus());
    }

    private boolean isTerminalVerdict(String resultStatus) {
        if (resultStatus == null || resultStatus.isBlank()) {
            return false;
        }
        return switch (resultStatus.trim().toUpperCase(Locale.ROOT)) {
            case "VERIFIED", "TAMPERED", "FAILED", "UNAVAILABLE" -> true;
            default -> false;
        };
    }

    private boolean hashMatches(String actualHash, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return true;
        }
        if (actualHash == null || actualHash.isBlank()) {
            return false;
        }
        return normalizeHash(actualHash).equalsIgnoreCase(normalizeHash(expectedHash));
    }

    private String normalizeHash(String hash) {
        String normalized = hash == null ? "" : hash.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return normalized.substring(2);
        }
        return normalized;
    }

    private String resolveFirmwareStatus(String firmwareStatusOverride) {
        if (firmwareStatusOverride != null && !firmwareStatusOverride.isBlank()) {
            return firmwareStatusOverride.trim();
        }
        String configured = verificationProperties.getFirmwareStatus();
        if (configured == null || configured.isBlank()) {
            return "Downloaded";
        }
        return configured.trim();
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

    @FunctionalInterface
    public interface FirmwareStatusSender {
        void send(String status, String hash, String firmwareVersion) throws Exception;
    }

    public record RequestHashVerificationResult(
        EvVerificationGateState.Snapshot snapshot,
        String deliveredHash,
        String generationSource,
        boolean anyTampered,
        List<String> tamperedComponents
    ) {
    }

    record GeneratedHashResponse(
        String stationId,
        String hash,
        String generationSource,
        boolean anyTampered,
        List<String> tamperedComponents,
        String generatedAt
    ) {
    }

    record LatestVerdictResponse(
        String stationId,
        String resultStatus,
        boolean verified,
        String verificationStatus,
        String reportedHash,
        String expectedHash,
        Boolean signatureVerified,
        String contractAddress,
        String rpcStatus,
        String verificationTxHash,
        String message,
        String observedAt,
        String sourceEvent
    ) {
    }
}
