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

    private Web3j web3j;
    private Credentials credentials;
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

                ChargingEscrowContract contract = ChargingEscrowContract.deploy(
                        web3j,
                        credentials,
                        gasProvider,
                        credentials.getAddress(),
                        chargerWallet,
                        stationId,
                        goldenHashBytes,
                        BigInteger.valueOf(targetSoc),
                        BigInteger.valueOf(timeoutSeconds)
                ).send();

                String address = contract.getContractAddress();

                log.info("✅ Escrow deployed at {}", address);

                return address;

            } catch (Exception e) {
                log.error("❌ DEPLOY FAILED", e);
                throw new RuntimeException(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // AUTHORIZE SESSION
    // ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<String> authorizeSession(String escrowAddress, String liveHash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] liveHashBytes = hexToBytes32(liveHash);

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
                var receipt = loadEscrow(escrowAddress)
                        .startCharging(sessionId)
                        .send();

                log.info("✅ startCharging tx={}", receipt.getTransactionHash());

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
                var receipt = loadEscrow(escrowAddress)
                        .updateSoc(BigInteger.valueOf(soc))
                        .send();

                log.info("⚡ updateSoc={} tx={}", soc, receipt.getTransactionHash());

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
                var receipt = loadEscrow(escrowAddress)
                        .completeSession()
                        .send();

                log.info("✅ completeSession tx={}", receipt.getTransactionHash());

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
                var receipt = loadEscrow(escrowAddress)
                        .releaseFunds()
                        .send();

                log.info("💰 releaseFunds tx={}", receipt.getTransactionHash());

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
                log.error("🚨 SENDING REFUND TRANSACTION escrow={}", escrowAddress);

                var receipt = loadEscrow(escrowAddress)
                        .refund(reason)
                        .send();

                log.error("✅ REFUND TX SENT: {}", receipt.getTransactionHash());

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