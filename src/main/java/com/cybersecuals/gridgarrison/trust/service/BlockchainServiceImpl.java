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
import java.util.UUID;
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
        return verifyGoldenHashWithEvidence(firmwareHash)
            .thenApply(TrustVerificationResult::firmwareHash);
    }

    @Override
    public CompletableFuture<TrustVerificationResult> verifyGoldenHashWithEvidence(FirmwareHash firmwareHash) {
        log.info("[Trust] Verifying hash for stationId={}", firmwareHash.getStationId());

        return CompletableFuture.supplyAsync(() -> {
            Instant observedAt = Instant.now();
            long startedAtNanos = System.nanoTime();
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

                FirmwareHash output = FirmwareHash.builder()
                    .stationId(firmwareHash.getStationId())
                    .reportedHash(firmwareHash.getReportedHash())
                    .goldenHash(onChainHash)
                    .firmwareVersion(firmwareHash.getFirmwareVersion())
                    .reportedAt(observedAt)
                    .status(status)
                    .build();

                long latencyMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
                TrustEvidence evidence = new TrustEvidence(
                    firmwareHash.getStationId(),
                    mapVerdict(status),
                    firmwareHash.getReportedHash(),
                    onChainHash,
                    contractAddress,
                    null,
                    TrustEvidence.RpcStatus.REACHABLE,
                    observedAt,
                    buildRationale(status, firmwareHash.getReportedHash(), onChainHash),
                    latencyMs,
                    latencyBand(latencyMs)
                );

                return new TrustVerificationResult(output, evidence);

            } catch (Exception e) {
                log.error("[Trust] On-chain lookup failed for stationId={}",
                    firmwareHash.getStationId(), e);
                FirmwareHash output = FirmwareHash.builder()
                    .stationId(firmwareHash.getStationId())
                    .reportedHash(firmwareHash.getReportedHash())
                    .reportedAt(observedAt)
                    .status(FirmwareHash.VerificationStatus.UNKNOWN_STATION)
                    .build();

                String reason = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
                TrustEvidence evidence = TrustEvidence.infrastructureFailure(
                    firmwareHash.getStationId(),
                    firmwareHash.getReportedHash(),
                    contractAddress,
                    "Blockchain RPC error: " + reason,
                    observedAt
                );
                return new TrustVerificationResult(output, evidence);
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
                String reason = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
                log.warn("[Trust] Session event record fell back to dry-run — stationId={} reason={}",
                    stationId, reason);
                return "DRYRUN-SESSION-" + UUID.randomUUID();
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

    private TrustEvidence.Verdict mapVerdict(FirmwareHash.VerificationStatus status) {
        if (status == FirmwareHash.VerificationStatus.VERIFIED) {
            return TrustEvidence.Verdict.VERIFIED;
        }
        if (status == FirmwareHash.VerificationStatus.TAMPERED) {
            return TrustEvidence.Verdict.TAMPERED;
        }
        return TrustEvidence.Verdict.UNKNOWN_STATION;
    }

    private String buildRationale(FirmwareHash.VerificationStatus status,
                                  String reported,
                                  String onChain) {
        if (status == FirmwareHash.VerificationStatus.VERIFIED) {
            return "Reported hash matches on-chain golden hash.";
        }
        if (status == FirmwareHash.VerificationStatus.TAMPERED) {
            return "Reported hash differs from on-chain golden hash.";
        }
        if (onChain == null || onChain.isBlank() || "0x".equalsIgnoreCase(onChain)) {
            return "No on-chain golden hash found for station.";
        }
        if (reported == null || reported.isBlank()) {
            return "Reported hash missing from firmware payload.";
        }
        return "Unable to determine trust verdict.";
    }

    private TrustEvidence.LatencyBand latencyBand(long latencyMs) {
        if (latencyMs <= 0) {
            return TrustEvidence.LatencyBand.UNKNOWN;
        }
        if (latencyMs < 250) {
            return TrustEvidence.LatencyBand.FAST;
        }
        if (latencyMs < 1000) {
            return TrustEvidence.LatencyBand.MODERATE;
        }
        return TrustEvidence.LatencyBand.SLOW;
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
