package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.cybersecuals.gridgarrison.trust.contract.FirmwareRegistryContract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Web3j-backed implementation of {@link BlockchainService}.
 * Package-private — callers depend on the interface, not this class.
 *
 * Configuration via {@code application.yml}:
 * <pre>
 * gridgarrison:
 *   blockchain:
 *     rpc-url:           https://your-eth-node/rpc
 *     private-key:       0xDEADBEEF...          # admin wallet for write ops
 *     contract-address:  0xABCD...               # deployed FirmwareRegistry
 * </pre>
 */
@Slf4j
@Service
class BlockchainServiceImpl implements BlockchainService {

    @Value("${gridgarrison.blockchain.rpc-url}")
    private String rpcUrl;

    @Value("${gridgarrison.blockchain.private-key}")
    private String privateKey;

    @Value("${gridgarrison.blockchain.contract-address}")
    private String contractAddress;

    private Web3j             web3j;
    private Credentials       credentials;
    private FirmwareRegistryContract firmwareRegistry;

    @PostConstruct
    void init() {
        web3j       = Web3j.build(new HttpService(rpcUrl));
        credentials = Credentials.create(privateKey);
        firmwareRegistry = FirmwareRegistryContract.load(
            contractAddress, web3j, credentials, new DefaultGasProvider()
        );
        log.info("[Trust] Web3j connected — rpc={} contract={}", rpcUrl, contractAddress);
    }

    // -------------------------------------------------------------------------
    // Public API implementation
    // -------------------------------------------------------------------------

    @Async
    @Override
    public CompletableFuture<FirmwareHash> verifyGoldenHash(FirmwareHash firmwareHash) {
        log.info("[Trust] Verifying hash for stationId={}", firmwareHash.getStationId());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Call read-only contract function: getGoldenHash(stationId)
                String onChainHash = firmwareRegistry
                    .getGoldenHash(firmwareHash.getStationId())
                    .send();

                FirmwareHash.VerificationStatus status = determineStatus(
                    firmwareHash.getReportedHash(), onChainHash
                );

                log.info("[Trust] Verification result — stationId={} status={}",
                    firmwareHash.getStationId(), status);

                return FirmwareHash.builder()
                    .stationId(firmwareHash.getStationId())
                    .reportedHash(firmwareHash.getReportedHash())
                    .goldenHash(onChainHash)
                    .firmwareVersion(firmwareHash.getFirmwareVersion())
                    .reportedAt(firmwareHash.getReportedAt())
                    .status(status)
                    .build();

            } catch (Exception e) {
                log.error("[Trust] On-chain lookup failed for stationId={}",
                    firmwareHash.getStationId(), e);
                return FirmwareHash.builder()
                    .stationId(firmwareHash.getStationId())
                    .reportedHash(firmwareHash.getReportedHash())
                    .reportedAt(Instant.now())
                    .status(FirmwareHash.VerificationStatus.UNKNOWN_STATION)
                    .build();
            }
        });
    }

    @Async
    @Override
    public CompletableFuture<String> registerGoldenHash(String stationId,
                                                         String goldenHash) {
        log.info("[Trust] Registering golden hash — stationId={}", stationId);

        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = firmwareRegistry
                    .registerGoldenHash(stationId, goldenHash)
                    .send();
                log.info("[Trust] Hash registered — txHash={}", receipt.getTransactionHash());
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("Failed to register golden hash for " + stationId, e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private FirmwareHash.VerificationStatus determineStatus(String reported, String onChain) {
        if (onChain == null || onChain.isBlank() || onChain.equals("0x")) {
            return FirmwareHash.VerificationStatus.UNKNOWN_STATION;
        }
        return normalise(reported).equalsIgnoreCase(normalise(onChain))
            ? FirmwareHash.VerificationStatus.VERIFIED
            : FirmwareHash.VerificationStatus.TAMPERED;
    }

    private String normalise(String hash) {
        return hash.startsWith("0x") ? hash.substring(2) : hash;
    }
}
