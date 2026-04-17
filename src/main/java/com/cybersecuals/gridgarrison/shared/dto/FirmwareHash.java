package com.cybersecuals.gridgarrison.shared.dto;

import java.time.Instant;

/**
 * Public DTO carrying firmware integrity data.
 * The {@code goldenHash} field is verified by the {@code trust} module
 * against the on-chain smart contract registry.
 */
public record FirmwareHash(

    /** Station that reported this firmware image. */
    String stationId,

    /**
     * SHA-256 hash of the firmware image as reported by the station.
     * Prefixed with "0x" when submitted to the smart contract.
     */
    String reportedHash,

    /**
     * The authoritative "Golden Hash" stored on-chain.
     * Populated by {@code BlockchainService} after verification; null until resolved.
     */
    String goldenHash,

    /** Firmware semantic version string (e.g. "2.1.4"). */
    String firmwareVersion,

    /** Manufacturer identity associated with the golden hash signature. */
    String manufacturerId,

    /** Manufacturer signature for the golden hash (Base64). */
    String manufacturerSignature,

    /** Whether manufacturer signature verification succeeded. */
    Boolean signatureVerified,

    /** Timestamp when the station reported this firmware. */
    Instant reportedAt,

    /** Verification outcome — set by the trust module after on-chain lookup. */
    VerificationStatus status
) {

    public static Builder builder() {
        return new Builder();
    }

    // Keep legacy getter-style methods for existing call sites.
    public String getStationId() {
        return stationId;
    }

    public String getReportedHash() {
        return reportedHash;
    }

    public String getGoldenHash() {
        return goldenHash;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public String getManufacturerId() {
        return manufacturerId;
    }

    public String getManufacturerSignature() {
        return manufacturerSignature;
    }

    public Boolean getSignatureVerified() {
        return signatureVerified;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public static final class Builder {
        private String stationId;
        private String reportedHash;
        private String goldenHash;
        private String firmwareVersion;
        private String manufacturerId;
        private String manufacturerSignature;
        private Boolean signatureVerified;
        private Instant reportedAt;
        private VerificationStatus status;

        public Builder stationId(String stationId) {
            this.stationId = stationId;
            return this;
        }

        public Builder reportedHash(String reportedHash) {
            this.reportedHash = reportedHash;
            return this;
        }

        public Builder goldenHash(String goldenHash) {
            this.goldenHash = goldenHash;
            return this;
        }

        public Builder firmwareVersion(String firmwareVersion) {
            this.firmwareVersion = firmwareVersion;
            return this;
        }

        public Builder manufacturerId(String manufacturerId) {
            this.manufacturerId = manufacturerId;
            return this;
        }

        public Builder manufacturerSignature(String manufacturerSignature) {
            this.manufacturerSignature = manufacturerSignature;
            return this;
        }

        public Builder signatureVerified(Boolean signatureVerified) {
            this.signatureVerified = signatureVerified;
            return this;
        }

        public Builder reportedAt(Instant reportedAt) {
            this.reportedAt = reportedAt;
            return this;
        }

        public Builder status(VerificationStatus status) {
            this.status = status;
            return this;
        }

        public FirmwareHash build() {
            return new FirmwareHash(
                stationId,
                reportedHash,
                goldenHash,
                firmwareVersion,
                manufacturerId,
                manufacturerSignature,
                signatureVerified,
                reportedAt,
                status
            );
        }
    }

    public enum VerificationStatus {
        PENDING,
        VERIFIED,
        TAMPERED,
        UNKNOWN_STATION
    }
}
