package com.cybersecuals.gridgarrison.trust.service;

import java.time.Instant;
import java.util.concurrent.CompletionException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;

@RestController
@RequestMapping("/trust/api")
class TrustReadController {

    private final BlockchainService blockchainService;

    TrustReadController(BlockchainService blockchainService) {
        this.blockchainService = blockchainService;
    }

    @GetMapping("/golden-hash")
    OnChainGoldenHashResponse goldenHash(
        @RequestParam(defaultValue = "CS-101") String stationId
    ) {
        try {
            OnChainGoldenRecord record = blockchainService.fetchOnChainGoldenRecord(stationId).join();
            if (record.goldenHash() == null || record.goldenHash().isBlank()) {
                return new OnChainGoldenHashResponse(
                    stationId,
                    null,
                    record.manufacturerId(),
                    record.contractAddress(),
                    record.observedAt(),
                    "MISSING",
                    "No golden hash found on-chain for this station."
                );
            }

            return new OnChainGoldenHashResponse(
                stationId,
                record.goldenHash(),
                record.manufacturerId(),
                record.contractAddress(),
                record.observedAt(),
                "OK",
                "Live on-chain golden hash loaded."
            );
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
            return new OnChainGoldenHashResponse(
                stationId,
                null,
                null,
                null,
                Instant.now(),
                "UNAVAILABLE",
                message
            );
        }
    }

    @PostMapping("/verify-firmware")
    VerifyFirmwareResponse verifyFirmware(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam String reportedHash,
        @RequestParam(defaultValue = "1.0.0") String firmwareVersion
    ) {
        if (reportedHash == null || reportedHash.isBlank()) {
            return new VerifyFirmwareResponse(
                stationId,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "FAILED",
                "reportedHash is required"
            );
        }

        FirmwareHash input = FirmwareHash.builder()
            .stationId(stationId)
            .reportedHash(reportedHash)
            .firmwareVersion(firmwareVersion)
            .reportedAt(Instant.now())
            .status(FirmwareHash.VerificationStatus.PENDING)
            .build();

        try {
            TrustVerificationResult verificationResult = blockchainService
                .verifyGoldenHashWithEvidence(input)
                .join();

            FirmwareHash output = verificationResult.firmwareHash();
            TrustEvidence evidence = verificationResult.evidence();
            boolean verified = output.getStatus() == FirmwareHash.VerificationStatus.VERIFIED;

            return new VerifyFirmwareResponse(
                output.getStationId(),
                verified,
                output.getStatus() == null ? null : output.getStatus().name(),
                output.getReportedHash(),
                output.getGoldenHash(),
                output.getSignatureVerified(),
                evidence == null ? null : evidence.contractAddress(),
                evidence == null || evidence.rpcStatus() == null ? null : evidence.rpcStatus().name(),
                evidence == null ? null : evidence.txHash(),
                evidence == null ? output.getReportedAt() : evidence.observedAt(),
                verified ? "OK" : "MISMATCH",
                evidence == null ? "Verification completed." : evidence.rationale()
            );
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
            return new VerifyFirmwareResponse(
                stationId,
                false,
                FirmwareHash.VerificationStatus.UNKNOWN_STATION.name(),
                reportedHash,
                null,
                null,
                null,
                TrustEvidence.RpcStatus.UNREACHABLE.name(),
                null,
                Instant.now(),
                "UNAVAILABLE",
                message
            );
        }
    }

    @PostMapping("/register-runtime-signed-baseline")
    SignedBaselineRegistrationResponse registerRuntimeSignedBaseline(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam String goldenHash,
        @RequestParam(defaultValue = "false") boolean forceOverwrite
    ) {
        try {
            SignedGoldenRegistrationResult result = blockchainService
                .registerRuntimeSignedGoldenHash(stationId, goldenHash, forceOverwrite)
                .join();
            return new SignedBaselineRegistrationResponse(
                result.stationId(),
                result.goldenHash(),
                result.manufacturerId(),
                result.txHash(),
                result.observedAt(),
                result.overwritten(),
                "OK",
                result.overwritten()
                    ? "Signed baseline replaced existing on-chain hash."
                    : "Signed baseline stored on-chain."
            );
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof IllegalStateException) {
                return new SignedBaselineRegistrationResponse(
                    stationId,
                    null,
                    null,
                    null,
                    Instant.now(),
                    false,
                    "CONFLICT",
                    cause.getMessage() == null ? "Baseline already exists on-chain." : cause.getMessage()
                );
            }

            String message = cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
            return new SignedBaselineRegistrationResponse(
                stationId,
                null,
                null,
                null,
                Instant.now(),
                false,
                "FAILED",
                message
            );
        }
    }

    record OnChainGoldenHashResponse(
        String stationId,
        String goldenHash,
        String manufacturerId,
        String contractAddress,
        Instant observedAt,
        String status,
        String message
    ) {
    }

    record SignedBaselineRegistrationResponse(
        String stationId,
        String goldenHash,
        String manufacturerId,
        String txHash,
        Instant observedAt,
        boolean overwritten,
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
        Instant observedAt,
        String status,
        String message
    ) {
    }
}