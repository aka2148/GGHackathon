package com.cybersecuals.gridgarrison.simulator;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class EvVerificationGateState {

    public enum GateStatus {
        UNVERIFIED,
        PENDING,
        VERIFIED,
        FAILED
    }

    public record Snapshot(
        GateStatus gateStatus,
        boolean chargingAllowed,
        String stationId,
        String reportedHash,
        String goldenHash,
        String verificationStatus,
        Boolean signatureVerified,
        String contractAddress,
        String rpcStatus,
        String message,
        Instant updatedAt
    ) {
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
        new Snapshot(
            GateStatus.UNVERIFIED,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "Waiting for first verification after connect.",
            Instant.now()
        )
    );

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public boolean isVerifiedForCharging() {
        return snapshot.get().gateStatus() == GateStatus.VERIFIED;
    }

    public String blockReason() {
        Snapshot current = snapshot.get();
        if (current.gateStatus() == GateStatus.VERIFIED) {
            return null;
        }
        if (current.message() == null || current.message().isBlank()) {
            return "Firmware verification has not succeeded yet.";
        }
        return current.message();
    }

    public void markPending(String stationId, String reportedHash, String message) {
        snapshot.set(new Snapshot(
            GateStatus.PENDING,
            false,
            stationId,
            reportedHash,
            null,
            null,
            null,
            null,
            null,
            message,
            Instant.now()
        ));
    }

    public void markVerified(String stationId,
                             String reportedHash,
                             String goldenHash,
                             String verificationStatus,
                             Boolean signatureVerified,
                             String contractAddress,
                             String rpcStatus,
                             String message) {
        snapshot.set(new Snapshot(
            GateStatus.VERIFIED,
            true,
            stationId,
            reportedHash,
            goldenHash,
            verificationStatus,
            signatureVerified,
            contractAddress,
            rpcStatus,
            message,
            Instant.now()
        ));
    }

    public void markFailed(String stationId,
                           String reportedHash,
                           String goldenHash,
                           String verificationStatus,
                           Boolean signatureVerified,
                           String contractAddress,
                           String rpcStatus,
                           String message) {
        snapshot.set(new Snapshot(
            GateStatus.FAILED,
            false,
            stationId,
            reportedHash,
            goldenHash,
            verificationStatus,
            signatureVerified,
            contractAddress,
            rpcStatus,
            message,
            Instant.now()
        ));
    }

    public void reset(String stationId, String message) {
        snapshot.set(new Snapshot(
            GateStatus.UNVERIFIED,
            false,
            stationId,
            null,
            null,
            null,
            null,
            null,
            null,
            message == null || message.isBlank()
                ? "Waiting for first verification after reset."
                : message,
            Instant.now()
        ));
    }
}
