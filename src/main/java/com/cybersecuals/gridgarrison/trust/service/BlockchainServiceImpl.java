package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.cybersecuals.gridgarrison.trust.contract.FirmwareRegistryContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${gridgarrison.blockchain.rpc-url}")
    private String rpcUrl;

    @Value("${gridgarrison.blockchain.private-key}")
    private String privateKey;

    @Value("${gridgarrison.blockchain.contract-address}")
    private String contractAddress;

    @Value("${gridgarrison.blockchain.gas-price-wei:2000000000}")
    private BigInteger gasPriceWei;

    @Value("${gridgarrison.blockchain.gas-limit:6000000}")
    private BigInteger gasLimit;

    private Web3j             web3j;
    private Credentials       credentials;
    private FirmwareRegistryContract firmwareRegistry;
    private ContractGasProvider gasProvider;

    @PostConstruct
    void init() {
        web3j       = Web3j.build(new HttpService(rpcUrl));
        credentials = Credentials.create(privateKey);
        gasProvider = new StaticGasProvider(gasPriceWei, gasLimit);
        bindContract(contractAddress);
        log.info("[Trust] Web3j connected — rpc={} contract={}", rpcUrl, contractAddress);
    }

    synchronized String deployContract() {
        try {
            FirmwareRegistryContract deployed = FirmwareRegistryContract
                .deploy(web3j, credentials, gasProvider)
                .send();
            String deployedAddress = deployed.getContractAddress();
            bindContract(deployedAddress);
            log.info("[Trust] FirmwareRegistry deployed — contract={}", deployedAddress);
            return deployedAddress;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deploy FirmwareRegistry contract", e);
        }
    }

    synchronized void bindContract(String address) {
        this.contractAddress = address;
        this.firmwareRegistry = FirmwareRegistryContract.load(
            address, web3j, credentials, gasProvider
        );
    }

    // -------------------------------------------------------------------------
    // Public API implementation
    // -------------------------------------------------------------------------

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

    @Override
    public CompletableFuture<String> recordSessionEvent(String stationId,
                                                         String sessionId,
                                                         String state) {
        log.info("[Trust] Recording session event — stationId={} sessionId={} state={}",
            stationId, sessionId, state);

        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = firmwareRegistry
                    .recordSessionEvent(stationId, sessionId, normaliseState(state))
                    .send();
                log.info("[Trust] Session event recorded — txHash={}", receipt.getTransactionHash());
                return receipt.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException("Failed to record session event for " + stationId, e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private FirmwareHash.VerificationStatus determineStatus(String reported, String onChain) {
        if (reported == null || reported.isBlank()) {
            return FirmwareHash.VerificationStatus.UNKNOWN_STATION;
        }
        if (onChain == null || onChain.isBlank() || onChain.equals("0x")) {
            return FirmwareHash.VerificationStatus.UNKNOWN_STATION;
        }
        return normalise(reported).equalsIgnoreCase(normalise(onChain))
            ? FirmwareHash.VerificationStatus.VERIFIED
            : FirmwareHash.VerificationStatus.TAMPERED;
    }

    private String normalise(String hash) {
        if (hash == null) {
            return "";
        }
        return hash.startsWith("0x") ? hash.substring(2) : hash;
    }

    private String normaliseState(String state) {
        if (state == null || state.isBlank()) {
            return "UPDATE";
        }
        return state.trim().toUpperCase();
    }

    String extractSessionId(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            if (payload == null) {
                return "SESSION-UNKNOWN";
            }
            String id = textValue(payload, "sessionId", "transactionId", "txId");
            return (id == null || id.isBlank()) ? "SESSION-UNKNOWN" : id;
        } catch (Exception ignored) {
            return "SESSION-UNKNOWN";
        }
    }

    String extractSessionState(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            if (payload == null) {
                return "UPDATE";
            }
            String state = textValue(payload, "state", "eventType", "type");
            return (state == null || state.isBlank()) ? "UPDATE" : state.toUpperCase();
        } catch (Exception ignored) {
            return "UPDATE";
        }
    }

    private String textValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
