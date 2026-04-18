package com.cybersecuals.gridgarrison.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
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

    @Value("${ev.simulator.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${ev.simulator.tls.key-store-path:}")
    private String tlsKeyStorePath;

    @Value("${ev.simulator.tls.key-store-password:}")
    private String tlsKeyStorePassword;

    @Value("${ev.simulator.tls.key-store-type:PKCS12}")
    private String tlsKeyStoreType;

    @Value("${ev.simulator.tls.trust-store-path:}")
    private String tlsTrustStorePath;

    @Value("${ev.simulator.tls.trust-store-password:}")
    private String tlsTrustStorePassword;

    @Value("${ev.simulator.tls.trust-store-type:PKCS12}")
    private String tlsTrustStoreType;

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
        RestTemplateBuilder builder = restTemplateBuilder
            .setConnectTimeout(Duration.ofMillis(Math.max(100, verificationProperties.getConnectTimeoutMs())))
            .setReadTimeout(Duration.ofMillis(Math.max(100, verificationProperties.getReadTimeoutMs())));

        if (tlsEnabled) {
            try {
                SSLContext sslContext = buildSslContext();
                builder = builder.requestFactory(() -> createTlsRequestFactory(sslContext));
                log.info("Custom TLS context configured for verification REST client");
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to configure TLS for verification REST client", ex);
            }
        }

        restTemplate = builder.build();
    }

    private SimpleClientHttpRequestFactory createTlsRequestFactory(SSLContext sslContext) {
        return new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (connection instanceof HttpsURLConnection httpsConnection) {
                    httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                }
            }
        };
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore trustStore = loadKeyStore(tlsTrustStorePath, tlsTrustStoreType, tlsTrustStorePassword);
        KeyStore keyStore = loadOptionalKeyStore(tlsKeyStorePath, tlsKeyStoreType, tlsKeyStorePassword);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        if (keyStore != null) {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, safePassword(tlsKeyStorePassword));
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        } else {
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        }
        return sslContext;
    }

    private KeyStore loadOptionalKeyStore(String path, String type, String password) throws Exception {
        if (path == null || path.isBlank()) {
            return null;
        }
        return loadKeyStore(path, type, password);
    }

    private KeyStore loadKeyStore(String path, String type, String password) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("TLS store path is required when ev.simulator.tls.enabled=true");
        }

        KeyStore keyStore = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
        try (InputStream inputStream = openStoreStream(path)) {
            keyStore.load(inputStream, safePassword(password));
        }
        return keyStore;
    }

    private InputStream openStoreStream(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String classpathLocation = path.substring("classpath:".length());
            InputStream stream = getClass().getResourceAsStream(
                classpathLocation.startsWith("/") ? classpathLocation : "/" + classpathLocation
            );
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + path);
            }
            return stream;
        }
        return new FileInputStream(path);
    }

    private char[] safePassword(String password) {
        return password == null ? new char[0] : password.toCharArray();
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

    public LatestVerdictResponse latestVerdict(String stationId) {
        return fetchLatestVerdict(stationId);
    }

    public EscrowIntentResponse submitEscrowIntent(String stationId,
                                                   BigInteger holdAmountWei,
                                                   Integer targetSoc,
                                                   Long timeoutSeconds,
                                                   String chargerWallet) {
        UriComponentsBuilder builder = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getEscrowIntentPath()))
            .queryParam("stationId", stationId);

        if (holdAmountWei != null) {
            builder.queryParam("holdAmountWei", holdAmountWei.toString());
        }
        if (targetSoc != null) {
            builder.queryParam("targetSoc", targetSoc);
        }
        if (timeoutSeconds != null) {
            builder.queryParam("timeoutSeconds", timeoutSeconds);
        }
        if (chargerWallet != null && !chargerWallet.isBlank()) {
            builder.queryParam("chargerWallet", chargerWallet);
        }

        return restTemplate.postForObject(builder.toUriString(), null, EscrowIntentResponse.class);
    }

    public EscrowActiveResponse escrowActive(String stationId) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getEscrowActivePath()))
            .queryParam("stationId", stationId)
            .toUriString();
        return restTemplate.getForObject(url, EscrowActiveResponse.class);
    }

    public EscrowActiveResponse resetEscrowBinding(String stationId, boolean clearIntent) {
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getEscrowResetPath()))
            .queryParam("stationId", stationId)
            .queryParam("clearIntent", clearIntent)
            .toUriString();
        return restTemplate.postForObject(url, null, EscrowActiveResponse.class);
    }

    public EscrowActiveResponse awaitEscrowActive(String stationId) {
        EvVerificationProperties.EscrowPoll poll = verificationProperties.getEscrowPoll();
        int maxAttempts = Math.max(1, poll.getMaxAttempts());
        long intervalMs = Math.max(100L, poll.getIntervalMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            EscrowActiveResponse active = escrowActive(stationId);
            if (active != null && (isEscrowReady(active) || isEscrowTerminalState(active.lifecycleState()))) {
                return active;
            }
            sleep(intervalMs);
        }

        return escrowActive(stationId);
    }

    public EscrowActiveResponse awaitEscrowLifecycle(String stationId,
                                                     String targetLifecycleState,
                                                     int maxAttempts,
                                                     long intervalMs) {
        String target = normalizeLifecycleState(targetLifecycleState);
        int safeAttempts = Math.max(1, maxAttempts);
        long safeIntervalMs = Math.max(100L, intervalMs);

        for (int attempt = 1; attempt <= safeAttempts; attempt++) {
            EscrowActiveResponse active = escrowActive(stationId);
            if (active == null) {
                sleep(safeIntervalMs);
                continue;
            }

            String lifecycleState = normalizeLifecycleState(active.lifecycleState());
            if (target.equals(lifecycleState)) {
                return active;
            }

            if (isEscrowSettlementTerminalState(lifecycleState) && !target.equals(lifecycleState)) {
                return active;
            }

            sleep(safeIntervalMs);
        }

        return escrowActive(stationId);
    }

    public WatchdogStationMetricsResponse watchdogStationMetrics(String stationId) {
        String resolvedStationId = stationId == null || stationId.isBlank() ? "CS-101" : stationId.trim();
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getWatchdogMetricsPath()))
            .queryParam("stationId", resolvedStationId)
            .toUriString();
        return restTemplate.getForObject(url, WatchdogStationMetricsResponse.class);
    }

    public WatchdogTelemetryResponse watchdogTelemetry(WatchdogTelemetryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("watchdog telemetry request is required");
        }
        String url = normalizeBaseUrl() + normalizePath(verificationProperties.getWatchdogTelemetryPath());
        return restTemplate.postForObject(url, request, WatchdogTelemetryResponse.class);
    }

    public WatchdogGoldenHashSyncResponse syncWatchdogGoldenHash(String stationId, String goldenHash) {
        if (goldenHash == null || goldenHash.isBlank()) {
            return new WatchdogGoldenHashSyncResponse(false, stationId, null, null,
                "Golden hash is empty. Skipping watchdog sync.");
        }

        String resolvedStationId = stationId == null || stationId.isBlank() ? "CS-101" : stationId.trim();
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getWatchdogGoldenHashPath()))
            .queryParam("stationId", resolvedStationId)
            .queryParam("goldenHash", goldenHash)
            .toUriString();
        return restTemplate.postForObject(url, null, WatchdogGoldenHashSyncResponse.class);
    }

    public WatchdogResetResponse resetWatchdogStation(String stationId) {
        String resolvedStationId = stationId == null || stationId.isBlank() ? "CS-101" : stationId.trim();
        String url = UriComponentsBuilder
            .fromHttpUrl(normalizeBaseUrl() + normalizePath(verificationProperties.getWatchdogResetPath()))
            .queryParam("stationId", resolvedStationId)
            .toUriString();
        return restTemplate.postForObject(url, null, WatchdogResetResponse.class);
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

    private boolean isEscrowReady(EscrowActiveResponse active) {
        if (active == null || active.escrowAddress() == null || active.escrowAddress().isBlank()) {
            return false;
        }
        String state = active.lifecycleState();
        if (state == null || state.isBlank()) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return !"NOT_CREATED".equals(normalized) && !"CREATING".equals(normalized);
    }

    private boolean isEscrowTerminalState(String lifecycleState) {
        if (lifecycleState == null || lifecycleState.isBlank()) {
            return false;
        }
        return switch (lifecycleState.trim().toUpperCase(Locale.ROOT)) {
            case "CREATED", "FUNDED", "AUTHORIZED", "CHARGING", "COMPLETED", "RELEASED", "REFUNDED", "FAILED" -> true;
            default -> false;
        };
    }

    private boolean isEscrowSettlementTerminalState(String lifecycleState) {
        if (lifecycleState == null || lifecycleState.isBlank()) {
            return false;
        }
        return switch (lifecycleState.trim().toUpperCase(Locale.ROOT)) {
            case "RELEASED", "REFUNDED", "FAILED" -> true;
            default -> false;
        };
    }

    private String normalizeLifecycleState(String lifecycleState) {
        if (lifecycleState == null || lifecycleState.isBlank()) {
            return "UNKNOWN";
        }
        return lifecycleState.trim().toUpperCase(Locale.ROOT);
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
            return "https://localhost:8443";
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

    record EscrowIntentResponse(
        String status,
        String stationId,
        BigInteger holdAmountWei,
        Integer targetSoc,
        Long timeoutSeconds,
        String chargerWallet,
        String createdAt,
        String message
    ) {
    }

    record EscrowActiveResponse(
        String stationId,
        String escrowAddress,
        String deployTxHash,
        String depositTxHash,
        String lifecycleState,
        BigInteger heldAmountWei,
        String sessionId,
        String goldenHash,
        String chargerWallet,
        String createdAt,
        String lastUpdated,
        String message
    ) {
    }

    public record WatchdogStationMetricsResponse(
        Boolean ok,
        String stationId,
        WatchdogMetricsSnapshot metrics,
        String message
    ) {
    }

    public record WatchdogGoldenHashSyncResponse(
        Boolean ok,
        String stationId,
        String goldenHash,
        WatchdogMetricsSnapshot metrics,
        String message
    ) {
    }

    public record WatchdogResetResponse(
        Boolean ok,
        String stationId,
        WatchdogMetricsSnapshot metrics,
        String message
    ) {
    }

    public record WatchdogTelemetryResponse(
        Boolean ok,
        String stationId,
        String sessionId,
        String sampledAt,
        Boolean anomaliesDetected,
        Integer anomalyCount,
        List<String> anomalyTypes,
        String severity,
        WatchdogMetricsSnapshot metrics,
        String message
    ) {
    }

    public record WatchdogTelemetryRequest(
        String stationId,
        String sessionId,
        Instant sampledAt,
        Double powerKw,
        Double energyDeliveredKwh,
        Double expectedEnergyDeliveredKwh,
        Double expectedPowerKw,
        Double expectedSocPercent,
        Double elapsedRatio,
        Double driftThresholdPct,
        Double currentA,
        Double voltageV,
        Double connectorTempC,
        Double batteryTempC,
        Double socPercent,
        String reportedFirmwareHash,
        String connectorId
    ) {
    }

    public record WatchdogMetricsSnapshot(
        String twinStatus,
        Integer totalAnomalies,
        Integer chargingAnomalyCount,
        Integer powerAnomalyCount,
        Integer temperatureAnomalyCount,
        Integer socAnomalyCount,
        Integer firmwareMismatchCount,
        Boolean stationHashChanged,
        Boolean powerSurgeDetected,
        String lastSeverity,
        List<String> lastAnomalyTypes,
        String lastAnomalyAt,
        String lastHeartbeat,
        Double lastPowerKw,
        Double lastTempC,
        Double lastSocPercent,
        String goldenHash,
        Double avgEnergyPerSessionKwh,
        Integer recentSessions
    ) {
    }
}
