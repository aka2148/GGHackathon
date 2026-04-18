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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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

    @Value("${gridgarrison.blockchain.tx-retry.max-attempts:3}")
    private int txRetryMaxAttempts;

    @Value("${gridgarrison.blockchain.tx-retry.delay-ms:300}")
    private long txRetryDelayMs;

    private Web3j             web3j;
    private Credentials       credentials;
    private ContractGasProvider gasProvider;

    @PostConstruct
    void init() {
        web3j = Web3j.build(new HttpService(rpcUrl));
        credentials = Credentials.create(privateKey);
        gasProvider = new StaticGasProvider(gasPriceWei, gasLimit);

        log.info("✅ EscrowService initialised rpc={} wallet={}",
                rpcUrl, credentials.getAddress());
    }

    // ─────────────────────────────────────────────────────────────
    // DEPLOY ESCROW
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> deployEscrow(String stationId,
                                                  String chargerWallet,
                                                  String goldenHash,
                                                  int targetSoc,
                                                  long timeoutSeconds) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("🚀 Deploying escrow for station={}", stationId);

                byte[] goldenHashBytes = hexToBytes32(goldenHash);

                ChargingEscrowContract escrow = executeWithRetry("deployEscrow", stationId, () ->
                    ChargingEscrowContract.deploy(
                        web3j,
                        credentials,
                        gasProvider,
                        credentials.getAddress(),          // operator = this backend wallet
                        chargerWallet,                     // charger payable address
                        stationId,
                        goldenHashBytes,
                        BigInteger.valueOf(targetSoc),
                        BigInteger.valueOf(timeoutSeconds)
                    ).send()
                );

                String address = contract.getContractAddress();


                return address;
                throw new RuntimeException(e);
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

                var receipt = executeWithRetry("deposit", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .send()
                );
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
                var receipt = executeWithRetry("authorizeSession", escrowAddress, () ->

                var receipt = loadEscrow(escrowAddress)
                        .verifyStation(liveHashBytes)
                        .send();

                log.info("✅ verifyStation tx={}", receipt.getTransactionHash());

                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ AUTHORIZE FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // START CHARGING
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> startCharging(String escrowAddress, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = executeWithRetry("startCharging", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .startCharging(sessionId)
                        .send()
                );
                log.info("[Escrow] startCharging tx={} session={}", receipt.getTransactionHash(), sessionId);
                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ START FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // UPDATE SOC
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> updateSoc(String escrowAddress, int soc) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = executeWithRetry("updateSoc", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .updateSoc(BigInteger.valueOf(soc))
                        .send()
                );
                log.debug("[Escrow] updateSoc={} tx={}", soc, receipt.getTransactionHash());
                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ updateSoc FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // COMPLETE SESSION
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> completeSession(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = executeWithRetry("completeSession", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .completeSession()
                        .send()
                );
                log.info("[Escrow] completeSession tx={} escrow={}", receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ COMPLETE FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // RELEASE FUNDS
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> releaseFunds(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = executeWithRetry("releaseFunds", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .releaseFunds()
                        .send()
                );
                log.info("[Escrow] releaseFunds tx={} escrow={}", receipt.getTransactionHash(), escrowAddress);
                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ RELEASE FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // 🔥 REFUND (YOUR MAIN ISSUE FIXED HERE)
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> refundSession(String escrowAddress, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = executeWithRetry("refundSession", escrowAddress, () ->
                    loadEscrow(escrowAddress)
                        .refund(reason)
                        .send()
                );
                log.info("[Escrow] refund tx={} reason={}", receipt.getTransactionHash(), reason);
                return receipt.getTransactionHash();

            } catch (Exception e) {
                log.error("❌ REFUND FAILED HARD", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // READ STATE
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<BigInteger> getSessionState(String escrowAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadEscrow(escrowAddress).getState().send();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private ChargingEscrowContract loadEscrow(String address) {
        return ChargingEscrowContract.load(address, web3j, credentials, gasProvider);
    }

    private <T> T executeWithRetry(String operation, String target, EscrowCall<T> call) throws Exception {
        int attempts = Math.max(1, txRetryMaxAttempts);
        long delayMs = Math.max(50L, txRetryDelayMs);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return call.execute();
            } catch (Exception ex) {
                boolean retryable = isRetryableTransactionError(ex);
                if (!retryable || attempt == attempts) {
                    throw ex;
                }

                log.warn("[Escrow] {} transient chain error; retrying attempt {}/{} target={} reason={}",
                    operation, attempt + 1, attempts, target, summarizeError(ex));
                sleepQuietly(delayMs);
                delayMs = Math.min(2000L, delayMs * 2);
            }
        }

        throw new IllegalStateException("Unexpected retry loop termination for operation=" + operation);
    }

    private boolean isRetryableTransactionError(Exception ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("underpriced")
                    || normalized.contains("can't be replaced")
                    || normalized.contains("nonce too low")
                    || normalized.contains("correct nonce")
                    || normalized.contains("account has nonce")
                    || normalized.contains("tx has nonce")
                    || normalized.contains("already known")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String summarizeError(Exception ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return ex.getClass().getSimpleName();
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface EscrowCall<T> {
        T execute() throws Exception;
    }

    /**
     * Convert a 0x-prefixed hex string to a 32-byte array (bytes32 in Solidity).
     * Pads with leading zeros if shorter; truncates if longer.
     */
    private byte[] hexToBytes32(String hex) {
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;

        while (clean.length() < 64) clean = "0" + clean;
        if (clean.length() > 64) clean = clean.substring(clean.length() - 64);

        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}