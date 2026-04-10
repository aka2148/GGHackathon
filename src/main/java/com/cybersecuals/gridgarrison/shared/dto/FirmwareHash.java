package com.cybersecuals.gridgarrison.shared.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Public DTO carrying firmware integrity data.
 * The {@code goldenHash} field is verified by the {@code trust} module
 * against the on-chain smart contract registry.
 */
@Value
@Builder
public class FirmwareHash {

    /** Station that reported this firmware image. */
    String stationId;

    /**
     * SHA-256 hash of the firmware image as reported by the station.
     * Prefixed with "0x" when submitted to the smart contract.
     */
    String reportedHash;

    /**
     * The authoritative "Golden Hash" stored on-chain.
     * Populated by {@code BlockchainService} after verification; null until resolved.
     */
    String goldenHash;

    /** Firmware semantic version string (e.g. "2.1.4"). */
    String firmwareVersion;

    /** Timestamp when the station reported this firmware. */
    Instant reportedAt;

    /** Verification outcome — set by the trust module after on-chain lookup. */
    VerificationStatus status;

    public enum VerificationStatus {
        PENDING,
        VERIFIED,
        TAMPERED,
        UNKNOWN_STATION
    }
}
