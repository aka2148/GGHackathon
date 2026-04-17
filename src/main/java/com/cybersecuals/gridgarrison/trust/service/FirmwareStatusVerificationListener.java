package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.orchestrator.websocket.FirmwareStatusEvent;
import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;
import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Service
class FirmwareStatusVerificationListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(FirmwareStatusVerificationListener.class);

    private final BlockchainService blockchainService;
    private final ApplicationEventPublisher eventPublisher;

    @SuppressWarnings("unused")
    FirmwareStatusVerificationListener(BlockchainService blockchainService,
                                       ApplicationEventPublisher eventPublisher) {
        this.blockchainService = blockchainService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    void onFirmwareStatus(FirmwareStatusEvent event) {
        FirmwareHash firmwareHash;

        try {
            firmwareHash = parseFirmwareHash(event.stationId(), event.rawPayload());
        } catch (RuntimeException ex) {
            log.warn("[Trust] Unable to parse firmware payload for stationId={}", event.stationId(), ex);
            String reason = ex.getMessage() == null ? "Malformed firmware payload" : ex.getMessage();
            TrustEvidence evidence = TrustEvidence.infrastructureFailure(
                event.stationId(),
                null,
                "UNAVAILABLE",
                reason,
                Instant.now()
            );
            eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                event.stationId(), event.rawPayload(), reason, evidence
            ));
            return;
        }

        eventPublisher.publishEvent(new GoldenHashRequestedEvent(
            event.stationId(),
            "Driver requested on-chain golden hash for station " + event.stationId()
        ));
        eventPublisher.publishEvent(new CurrentStatusHashReportedEvent(
            event.stationId(),
            "Station reported live status hash " + firmwareHash.getReportedHash()
        ));

        CompletableFuture<TrustVerificationResult> verification =
            blockchainService.verifyGoldenHashWithEvidence(firmwareHash);
        verification.whenComplete((result, error) -> {
            if (error != null) {
                log.error("[Trust] Firmware verification failed for stationId={}", event.stationId(), error);
                String reason = (error.getMessage() == null || error.getMessage().isBlank())
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
                TrustEvidence evidence = TrustEvidence.infrastructureFailure(
                    event.stationId(),
                    firmwareHash.getReportedHash(),
                    "UNAVAILABLE",
                    "Trust verification future failed: " + reason,
                    Instant.now()
                );
                eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                    event.stationId(), event.rawPayload(), reason, evidence
                ));
                return;
            }

            FirmwareHash resolvedHash = result.firmwareHash();
            TrustEvidence evidence = result.evidence();
            if (Boolean.TRUE.equals(resolvedHash.getSignatureVerified())) {
                eventPublisher.publishEvent(new GoldenSignatureVerifiedEvent(
                    resolvedHash.getStationId(),
                    "Manufacturer signature verified using configured public key",
                    evidence
                ));
            } else {
                eventPublisher.publishEvent(new GoldenSignatureVerificationFailedEvent(
                    resolvedHash.getStationId(),
                    "Manufacturer signature verification failed",
                    evidence
                ));
            }

            FirmwareHash.VerificationStatus status = resolvedHash.getStatus();
            switch (status) {
                case VERIFIED -> eventPublisher.publishEvent(new GoldenHashVerifiedEvent(
                    resolvedHash.getStationId(),
                    event.rawPayload(),
                    resolvedHash.getReportedHash(),
                    resolvedHash.getGoldenHash(),
                    evidence
                ));
                case TAMPERED -> eventPublisher.publishEvent(new GoldenHashTamperedEvent(
                    resolvedHash.getStationId(),
                    event.rawPayload(),
                    resolvedHash.getReportedHash(),
                    resolvedHash.getGoldenHash(),
                    evidence
                ));
                default -> eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                    resolvedHash.getStationId(),
                    event.rawPayload(),
                    status.name(),
                    evidence
                ));
            }
        });
    }

    @EventListener
    @SuppressWarnings("unused")
    void onTransactionEvent(TransactionEvent event) {
        String sessionId = "SESSION-UNKNOWN";
        String state = "UPDATE";

        if (blockchainService instanceof BlockchainServiceImpl impl) {
            sessionId = impl.extractSessionId(event.rawPayload());
            state = impl.extractSessionState(event.rawPayload());
        }

        blockchainService
            .recordSessionEvent(event.stationId(), sessionId, state)
            .whenComplete((txHash, error) -> {
                if (error != null) {
                    log.error("[Trust] Failed to record on-chain session event for stationId={}",
                        event.stationId(), error);
                    return;
                }
                log.info("[Trust] On-chain session marker written stationId={} txHash={}",
                    event.stationId(), txHash);
            });
    }

    private FirmwareHash parseFirmwareHash(String stationId, String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            String reportedHash = readFirstText(payload,
                "reportedHash",
                "firmwareHash",
                "hash",
                "firmwareSha256",
                "sha256");
            String firmwareVersion = readFirstText(payload, "firmwareVersion", "version");
            String manufacturerId = readFirstText(payload,
                "manufacturerId",
                "manufacturer",
                "vendorId");
            String manufacturerSignature = readFirstText(payload,
                "manufacturerSignature",
                "signature",
                "signatureBase64");

            if (reportedHash == null || reportedHash.isBlank()) {
                throw new IllegalArgumentException("Firmware payload does not contain a reported hash");
            }

            return FirmwareHash.builder()
                .stationId(stationId)
                .reportedHash(reportedHash)
                .firmwareVersion(firmwareVersion)
                .manufacturerId(manufacturerId)
                .manufacturerSignature(manufacturerSignature)
                .reportedAt(Instant.now())
                .status(FirmwareHash.VerificationStatus.PENDING)
                .build();
        } catch (java.io.IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Malformed firmware status payload", ex);
        }
    }

    private String readFirstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isNull()) {
                String value = field.asText();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}

record GoldenHashVerifiedEvent(String stationId,
                               String rawPayload,
                               String reportedHash,
                               String goldenHash,
                               TrustEvidence evidence) {}

record GoldenHashRequestedEvent(String stationId,
                                String rawPayload) {}

record CurrentStatusHashReportedEvent(String stationId,
                                      String rawPayload) {}

record ManufactureHashGeneratedEvent(String stationId,
                                     String rawPayload) {}

record GoldenHashSignedEvent(String stationId,
                             String rawPayload) {}

record SignedGoldenHashStoredOnChainEvent(String stationId,
                                          String rawPayload,
                                          String txHash) {}

record GoldenSignatureVerifiedEvent(String stationId,
                                    String rawPayload,
                                    TrustEvidence evidence) {}

record GoldenSignatureVerificationFailedEvent(String stationId,
                                              String rawPayload,
                                              TrustEvidence evidence) {}

record GoldenHashTamperedEvent(String stationId,
                               String rawPayload,
                               String reportedHash,
                               String goldenHash,
                               TrustEvidence evidence) {}

record GoldenHashVerificationFailedEvent(String stationId,
                                         String rawPayload,
                                         String reason,
                                         TrustEvidence evidence) {}