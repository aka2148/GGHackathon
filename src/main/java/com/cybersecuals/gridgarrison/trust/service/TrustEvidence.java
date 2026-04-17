package com.cybersecuals.gridgarrison.trust.service;

import java.time.Instant;

/**
 * Judge-facing blockchain verification evidence for trust decisions.
 */
public record TrustEvidence(
    String stationId,
    Verdict verdict,
    String reportedHash,
    String expectedHash,
    String manufacturerId,
    String manufacturerSignature,
    Boolean signatureVerified,
    String contractAddress,
    String txHash,
    RpcStatus rpcStatus,
    Instant observedAt,
    String rationale,
    Long latencyMs,
    LatencyBand latencyBand
) {

    public enum Verdict {
        VERIFIED,
        TAMPERED,
        UNKNOWN_STATION,
        INFRA_FAILURE
    }

    public enum RpcStatus {
        REACHABLE,
        UNREACHABLE,
        UNKNOWN
    }

    public enum LatencyBand {
        FAST,
        MODERATE,
        SLOW,
        UNKNOWN
    }

    public static TrustEvidence infrastructureFailure(String stationId,
                                                      String reportedHash,
                                                      String contractAddress,
                                                      String rationale,
                                                      Instant observedAt) {
        return new TrustEvidence(
            stationId,
            Verdict.INFRA_FAILURE,
            reportedHash,
            null,
            null,
            null,
            null,
            contractAddress,
            null,
            RpcStatus.UNREACHABLE,
            observedAt,
            rationale,
            null,
            LatencyBand.UNKNOWN
        );
    }
}