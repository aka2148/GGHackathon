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
     * Read the current on-chain signed golden record for a station.
     *
     * @param stationId station identity
     * @return future resolved with the current on-chain snapshot
     */
    CompletableFuture<OnChainGoldenRecord> fetchOnChainGoldenRecord(String stationId);

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
     * Register a signed golden hash payload for a station.
     *
     * @param stationId station identity
     * @param goldenHash authoritative hash
     * @param manufacturerSignature Base64 signature over goldenHash
     * @param manufacturerId manufacturer identity label
     * @return transaction hash of the submitted transaction
     */
    CompletableFuture<String> registerSignedGoldenHash(String stationId,
                                                       String goldenHash,
                                                       String manufacturerSignature,
                                                       String manufacturerId);

    /**
     * Register a signed golden hash at runtime using the configured manufacturer private key.
     *
     * @param stationId station identity
     * @param goldenHash canonical hash to sign and store
     * @param forceOverwrite whether an existing on-chain baseline can be replaced
     * @return registration result with tx metadata
     */
    CompletableFuture<SignedGoldenRegistrationResult> registerRuntimeSignedGoldenHash(String stationId,
                                                                                      String goldenHash,
                                                                                      boolean forceOverwrite);

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
