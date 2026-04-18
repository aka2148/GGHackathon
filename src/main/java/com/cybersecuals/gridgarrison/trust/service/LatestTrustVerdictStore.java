package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
class LatestTrustVerdictStore {

    private final Map<String, TrustVerdictSnapshot> latestVerdictByStation = new ConcurrentHashMap<>();

    TrustVerdictSnapshot markPending(String stationId,
                                     String reportedHash,
                                     String message) {
        String resolvedStationId = resolveStationId(stationId);
        TrustVerdictSnapshot snapshot = new TrustVerdictSnapshot(
            resolvedStationId,
            VerdictResultStatus.PENDING.name(),
            false,
            FirmwareHash.VerificationStatus.PENDING.name(),
            reportedHash,
            null,
            null,
            null,
            null,
            null,
            message,
            Instant.now(),
            "FIRMWARE_STATUS_EVENT"
        );
        latestVerdictByStation.put(resolvedStationId, snapshot);
        return snapshot;
    }

    TrustVerdictSnapshot markCompleted(FirmwareHash firmwareHash,
                                       TrustEvidence evidence,
                                       String sourceEvent,
                                       String fallbackMessage) {
        String stationId = resolveStationId(firmwareHash == null ? null : firmwareHash.getStationId());
        FirmwareHash.VerificationStatus status = firmwareHash == null
            ? FirmwareHash.VerificationStatus.UNKNOWN_STATION
            : firmwareHash.getStatus();
        VerdictResultStatus resultStatus = mapResultStatus(status, evidence);
        boolean verified = status == FirmwareHash.VerificationStatus.VERIFIED;

        String reportedHash = firmwareHash == null ? null : firmwareHash.getReportedHash();
        String expectedHash = firmwareHash == null ? null : firmwareHash.getGoldenHash();
        if ((expectedHash == null || expectedHash.isBlank()) && evidence != null) {
            expectedHash = evidence.expectedHash();
        }

        String message = fallbackMessage;
        if (evidence != null && evidence.rationale() != null && !evidence.rationale().isBlank()) {
            message = evidence.rationale();
        }

        TrustVerdictSnapshot snapshot = new TrustVerdictSnapshot(
            stationId,
            resultStatus.name(),
            verified,
            status == null ? null : status.name(),
            reportedHash,
            expectedHash,
            firmwareHash == null ? null : firmwareHash.getSignatureVerified(),
            evidence == null ? null : evidence.contractAddress(),
            evidence == null || evidence.rpcStatus() == null ? null : evidence.rpcStatus().name(),
            evidence == null ? null : evidence.txHash(),
            message,
            evidence == null || evidence.observedAt() == null ? Instant.now() : evidence.observedAt(),
            sourceEvent == null || sourceEvent.isBlank() ? "TRUST_VERIFICATION" : sourceEvent
        );
        latestVerdictByStation.put(stationId, snapshot);
        return snapshot;
    }

    TrustVerdictSnapshot markFailed(String stationId,
                                    String reportedHash,
                                    String verificationStatus,
                                    String message,
                                    TrustEvidence evidence,
                                    String sourceEvent) {
        VerdictResultStatus resultStatus = VerdictResultStatus.FAILED;
        if (evidence != null && evidence.rpcStatus() == TrustEvidence.RpcStatus.UNREACHABLE) {
            resultStatus = VerdictResultStatus.UNAVAILABLE;
        }

        String resolvedStationId = resolveStationId(stationId);
        TrustVerdictSnapshot snapshot = new TrustVerdictSnapshot(
            resolvedStationId,
            resultStatus.name(),
            false,
            verificationStatus,
            reportedHash,
            evidence == null ? null : evidence.expectedHash(),
            null,
            evidence == null ? null : evidence.contractAddress(),
            evidence == null || evidence.rpcStatus() == null ? null : evidence.rpcStatus().name(),
            evidence == null ? null : evidence.txHash(),
            message,
            evidence == null || evidence.observedAt() == null ? Instant.now() : evidence.observedAt(),
            sourceEvent == null || sourceEvent.isBlank() ? "TRUST_VERIFICATION" : sourceEvent
        );
        latestVerdictByStation.put(resolvedStationId, snapshot);
        return snapshot;
    }

    TrustVerdictSnapshot latest(String stationId) {
        String resolvedStationId = resolveStationId(stationId);
        TrustVerdictSnapshot existing = latestVerdictByStation.get(resolvedStationId);
        if (existing != null) {
            return existing;
        }

        return new TrustVerdictSnapshot(
            resolvedStationId,
            VerdictResultStatus.UNVERIFIED.name(),
            false,
            FirmwareHash.VerificationStatus.PENDING.name(),
            null,
            null,
            null,
            null,
            null,
            null,
            "No verification event has been processed for this station yet.",
            Instant.now(),
            "NONE"
        );
    }

    private VerdictResultStatus mapResultStatus(FirmwareHash.VerificationStatus status,
                                                TrustEvidence evidence) {
        if (status == null) {
            return VerdictResultStatus.FAILED;
        }
        return switch (status) {
            case VERIFIED -> VerdictResultStatus.VERIFIED;
            case TAMPERED -> VerdictResultStatus.TAMPERED;
            case PENDING -> VerdictResultStatus.PENDING;
            default -> {
                if (evidence != null && evidence.rpcStatus() == TrustEvidence.RpcStatus.UNREACHABLE) {
                    yield VerdictResultStatus.UNAVAILABLE;
                }
                yield VerdictResultStatus.FAILED;
            }
        };
    }

    private String resolveStationId(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return "CS-101";
        }
        return stationId;
    }

    enum VerdictResultStatus {
        UNVERIFIED,
        PENDING,
        VERIFIED,
        TAMPERED,
        FAILED,
        UNAVAILABLE
    }

    record TrustVerdictSnapshot(
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
        Instant observedAt,
        String sourceEvent
    ) {
    }
}