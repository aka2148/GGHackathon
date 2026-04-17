package com.cybersecuals.gridgarrison.trust.service;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for the escrow lifecycle manager.
 *
 * Each method corresponds to one transition in the ChargingEscrow.sol state machine:
 *
 *   CREATED → FUNDED       (deposit — triggered by buyer, not this service)
 *   FUNDED  → AUTHORIZED   (authorizeSession — after hash VERIFIED)
 *   FUNDED  → REFUNDED     (refundSession    — after hash TAMPERED/UNKNOWN)
 *   AUTHORIZED → CHARGING  (startCharging    — after OCPP TransactionEvent START)
 *   CHARGING → (no change) (updateSoc        — on each MeterValues message)
 *   CHARGING → COMPLETED   (completeSession  — on OCPP TransactionEvent END / targetSoc reached)
 *   COMPLETED → RELEASED   (releaseFunds     — final payment to charger)
 *   any live → REFUNDED    (refundSession     — on anomaly / attack detected)
 */
public interface EscrowService {

    /**
     * Deploy a new ChargingEscrow contract for this session.
     * Called once per session before the buyer deposits funds.
     *
     * @param stationId      OCPP station identity
     * @param chargerWallet  Ethereum address of the charging station wallet
     * @param goldenHash     Authoritative firmware hash (bytes32 hex, 0x-prefixed)
     * @param targetSoc      Charge target percent (1–100)
     * @param timeoutSeconds Seconds after CHARGING starts before buyer can self-cancel
     * @return deployed contract address
     */
    CompletableFuture<String> deployEscrow(String stationId,
                                            String chargerWallet,
                                            String goldenHash,
                                            int    targetSoc,
                                            long   timeoutSeconds);

    /**
     * Deposits buyer funds into escrow.
     * Sends deposit() -> CREATED -> FUNDED.
     *
     * @param escrowAddress deployed escrow contract address
     * @param amountWei amount to deposit in wei
     * @return transaction hash
     */
    CompletableFuture<String> deposit(String escrowAddress, BigInteger amountWei);

    /**
     * Called by the backend after BlockchainServiceImpl confirms hash is VERIFIED.
     * Sends verifyStation() to the escrow contract → FUNDED → AUTHORIZED.
     *
     * @param escrowAddress  deployed escrow contract address
     * @param liveHash       hash the station reported (0x bytes32 hex)
     * @return transaction hash
     */
    CompletableFuture<String> authorizeSession(String escrowAddress, String liveHash);

    /**
     * Called by the backend after OCPP TransactionEvent START.
     * Sends startCharging() → AUTHORIZED → CHARGING.
     *
     * @param escrowAddress deployed escrow contract address
     * @param sessionId     OCPP transaction / session ID
     * @return transaction hash
     */
    CompletableFuture<String> startCharging(String escrowAddress, String sessionId);

    /**
     * Called on each OCPP MeterValues message.
     * Sends updateSoc() — no state change, purely for on-chain audit trail.
     *
     * @param escrowAddress deployed escrow contract address
     * @param soc           current state-of-charge (0–100)
     * @return transaction hash
     */
    CompletableFuture<String> updateSoc(String escrowAddress, int soc);

    /**
     * Called when targetSoc is reached OR OCPP TransactionEvent END is received.
     * Sends completeSession() → CHARGING → COMPLETED.
     *
     * @param escrowAddress deployed escrow contract address
     * @return transaction hash
     */
    CompletableFuture<String> completeSession(String escrowAddress);

    /**
     * Releases funds to the charger after session completes successfully.
     * Sends releaseFunds() → COMPLETED → RELEASED.
     *
     * @param escrowAddress deployed escrow contract address
     * @return transaction hash
     */
    CompletableFuture<String> releaseFunds(String escrowAddress);

    /**
     * Refunds the buyer. Called when:
     *  - hash verification returns TAMPERED or UNKNOWN_STATION
     *  - DigitalTwinServiceImpl raises an anomaly that reaches ALERT state
     *
     * @param escrowAddress deployed escrow contract address
     * @param reason        short description for the on-chain event log
     * @return transaction hash
     */
    CompletableFuture<String> refundSession(String escrowAddress, String reason);

    /**
     * Read the current SessionState enum value from the contract.
     * Used by the visualizer and monitoring endpoints.
     *
     * @param escrowAddress deployed escrow contract address
     * @return current state ordinal (maps to ChargingEscrow.SessionState)
     */
    CompletableFuture<BigInteger> getSessionState(String escrowAddress);
}
