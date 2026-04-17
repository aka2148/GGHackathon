package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;

import java.util.concurrent.CompletableFuture;

/**
 * Public API of the {@code trust} module.
 *
 * Provides asynchronous Golden Hash verification against the on-chain
 * GridGarrison firmware registry smart contract via Web3j.
 */
public interface BlockchainService {

    /**
     * Verify a station's reported firmware hash against the on-chain golden hash.
     *
     * @param firmwareHash DTO containing the station ID and the hash to verify
     * @return future resolved with an enriched {@link FirmwareHash} carrying
     *         the on-chain golden hash and a {@link FirmwareHash.VerificationStatus}
     */
    CompletableFuture<FirmwareHash> verifyGoldenHash(FirmwareHash firmwareHash);

    /**
     * Verify a station firmware hash and attach judge-facing on-chain evidence.
     *
     * @param firmwareHash DTO containing the station ID and hash to verify
     * @return future resolved with both verification output and blockchain evidence
     */
    CompletableFuture<TrustVerificationResult> verifyGoldenHashWithEvidence(FirmwareHash firmwareHash);

    /**
     * Register a new authoritative golden hash for a station (admin operation).
     * Submits a signed transaction to the smart contract.
     *
     * @param stationId       the station's identity string
     * @param goldenHash      SHA-256 hex string (0x-prefixed)
     * @return transaction hash of the submitted registration
     */
    CompletableFuture<String> registerGoldenHash(String stationId,
                                                  String goldenHash);

    /**
     * Record a charging-session state transition on-chain for auditability.
     *
     * @param stationId station identity
     * @param sessionId logical session id (or transaction id)
     * @param state     state label such as START, UPDATE, END
     * @return transaction hash of the emitted session event transaction
     */
    CompletableFuture<String> recordSessionEvent(String stationId,
                                                 String sessionId,
                                                 String state);
}
