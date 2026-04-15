package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.orchestrator.websocket.FirmwareStatusEvent;
import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;
import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
class FirmwareStatusVerificationListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BlockchainService blockchainService;
    private final ApplicationEventPublisher eventPublisher;

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
            eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                event.stationId(), event.rawPayload(), ex.getMessage()
            ));
            return;
        }

        CompletableFuture<FirmwareHash> verification = blockchainService.verifyGoldenHash(firmwareHash);
        verification.whenComplete((result, error) -> {
            if (error != null) {
                log.error("[Trust] Firmware verification failed for stationId={}", event.stationId(), error);
                eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                    event.stationId(), event.rawPayload(), error.getMessage()
                ));
                return;
            }

            FirmwareHash.VerificationStatus status = result.getStatus();
            if (status == FirmwareHash.VerificationStatus.VERIFIED) {
                eventPublisher.publishEvent(new GoldenHashVerifiedEvent(
                    result.getStationId(), event.rawPayload(), result.getReportedHash(), result.getGoldenHash()
                ));
            } else if (status == FirmwareHash.VerificationStatus.TAMPERED) {
                eventPublisher.publishEvent(new GoldenHashTamperedEvent(
                    result.getStationId(), event.rawPayload(), result.getReportedHash(), result.getGoldenHash()
                ));
            } else {
                eventPublisher.publishEvent(new GoldenHashVerificationFailedEvent(
                    result.getStationId(), event.rawPayload(), status.name()
                ));
            }
        });
    }

    @EventListener
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

            if (reportedHash == null || reportedHash.isBlank()) {
                throw new IllegalArgumentException("Firmware payload does not contain a reported hash");
            }

            return FirmwareHash.builder()
                .stationId(stationId)
                .reportedHash(reportedHash)
                .firmwareVersion(firmwareVersion)
                .reportedAt(Instant.now())
                .status(FirmwareHash.VerificationStatus.PENDING)
                .build();
        } catch (Exception ex) {
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
                               String goldenHash) {}

record GoldenHashTamperedEvent(String stationId,
                               String rawPayload,
                               String reportedHash,
                               String goldenHash) {}

record GoldenHashVerificationFailedEvent(String stationId,
                                     String rawPayload,
                                     String reason) {}