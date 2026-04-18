package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.trust.contract.ChargingEscrowContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.StaticGasProvider;

import jakarta.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Web3j-backed implementation of {@link EscrowService}.
 * Package-private — callers depend on the interface.
 *
 * Each public method loads a fresh ChargingEscrowContract instance bound to
 * the provided escrowAddress and submits the appropriate transaction.
 *
 * One ChargingEscrow contract is deployed per charging session — this service
 * does NOT maintain a registry of contracts. The escrowAddress is managed by
 * your session layer (e.g. stored in ChargingSession or a dedicated EscrowSession DTO).
 */
@Slf4j
@Service
class EscrowServiceImpl implements EscrowService {

    @Value("${gridgarrison.blockchain.rpc-url}")
    private String rpcUrl;

    @Value("${gridgarrison.blockchain.private-key}")
    private String privateKey;

    @Value("${gridgarrison.blockchain.gas-price-wei:2000000000}")
    private BigInteger gasPriceWei;

    @Value("${gridgarrison.blockchain.gas-limit:6000000}")
    private BigInteger gasLimit;

    private Web3j             web3j;
    private Credentials       credentials;
    private ContractGasProvider gasProvider;

    @PostConstruct
    void init() {
        web3j       = Web3j.build(new HttpService(rpcUrl));
        credentials = Credentials.create(privateKey);
        gasProvider = new StaticGasProvider(gasPriceWei, gasLimit);
        log.info("[Escrow] EscrowService initialised — rpc={}", rpcUrl);
    }

    // ── Deploy ────────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> deployEscrow(String stationId,
                                                   String chargerWallet,
                                                   String goldenHash,
                                                   int    targetSoc,
                                                   long   timeoutSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Convert 0x-prefixed goldenHash string to bytes32
                byte[] goldenHashBytes = hexToBytes32(goldenHash);

                ChargingEscrowContract escrow = ChargingEscrowContract.deploy(
                    web3j,
                    credentials,
                    gasProvider,
                    credentials.getAddress(),          // operator = this backend wallet
                    chargerWallet,                     // charger payable address
                    stationId,
                    goldenHashBytes,
                    BigInteger.valueOf(targetSoc),
                    BigInteger.valueOf(timeoutSeconds)
                ).send();

                String address = escrow.getContractAddress();
                log.info("[Escrow] Deployed for stationId={} escrow={}", stationId, address);
                return address;
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to deploy ChargingEscrow for station " + stationId, e);
            }
        });
    }

    // ── CREATED → FUNDED ──────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> deposit(String escrowAddress, BigInteger amountWei) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (amountWei == null || amountWei.signum() <= 0) {
                    throw new IllegalArgumentException("amountWei must be greater than zero");
                }

                var receipt = loadEscrow(escrowAddress)
                    .deposit(amountWei)
                    .send();
                log.info("[Escrow] deposit amountWei={} tx={} escrow={}",
                    amountWei, receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("deposit failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── FUNDED → AUTHORIZED ───────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> authorizeSession(String escrowAddress, String liveHash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] liveHashBytes = hexToBytes32(liveHash);
                var receipt = loadEscrow(escrowAddress)
                    .verifyStation(liveHashBytes)
                    .send();
                log.info("[Escrow] verifyStation tx={} escrow={}", receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("authorizeSession failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── AUTHORIZED → CHARGING ─────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> startCharging(String escrowAddress, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = loadEscrow(escrowAddress)
                    .startCharging(sessionId)
                    .send();
                log.info("[Escrow] startCharging tx={} session={}", receipt.getTransactionHash(), sessionId);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("startCharging failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── SoC update (CHARGING, no state change) ────────────────────────────────

    @Override
    public CompletableFuture<String> updateSoc(String escrowAddress, int soc) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = loadEscrow(escrowAddress)
                    .updateSoc(BigInteger.valueOf(soc))
                    .send();
                log.debug("[Escrow] updateSoc={} tx={}", soc, receipt.getTransactionHash());
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("updateSoc failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── CHARGING → COMPLETED ─────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> completeSession(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = loadEscrow(escrowAddress)
                    .completeSession()
                    .send();
                log.info("[Escrow] completeSession tx={} escrow={}", receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("completeSession failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── COMPLETED → RELEASED ─────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> releaseFunds(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = loadEscrow(escrowAddress)
                    .releaseFunds()
                    .send();
                log.info("[Escrow] releaseFunds tx={} escrow={}", receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("releaseFunds failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── Refund (FUNDED | AUTHORIZED | CHARGING → REFUNDED) ───────────────────

    @Override
    public CompletableFuture<String> refundSession(String escrowAddress, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = loadEscrow(escrowAddress)
                    .refund(reason)
                    .send();
                log.info("[Escrow] refund tx={} reason={}", receipt.getTransactionHash(), reason);
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("refundSession failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── Read state ────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<BigInteger> getSessionState(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadEscrow(escrowAddress).getState().send();
            } catch (Exception e) {
                throw new RuntimeException("getSessionState failed for escrow=" + escrowAddress, e);
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Load a contract wrapper bound to a specific deployed escrow address. */
    private ChargingEscrowContract loadEscrow(String address) {
        return ChargingEscrowContract.load(address, web3j, credentials, gasProvider);
    }

    /**
     * Convert a 0x-prefixed hex string to a 32-byte array (bytes32 in Solidity).
     * Pads with leading zeros if shorter; truncates if longer.
     */
    private byte[] hexToBytes32(String hex) {
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
        // Pad to 64 hex chars = 32 bytes
        while (clean.length() < 64) clean = "0" + clean;
        if (clean.length() > 64) clean = clean.substring(clean.length() - 64);
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
