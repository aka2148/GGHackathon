package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.cybersecuals.gridgarrison.trust.contract.FirmwareRegistryContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
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
    private static final String GOLDEN_HASH_FIELD = "goldenHash";
    private static final String SIGNATURE_FIELD = "manufacturerSignature";
    private static final String MANUFACTURER_FIELD = "manufacturerId";

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

    @Value("${gridgarrison.trust.manufacturer.id:ACME-MFG}")
    private String trustedManufacturerId;

    @Value("${gridgarrison.trust.manufacturer.public-key-base64:}")
    private String manufacturerPublicKeyBase64;

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
                GoldenRecord goldenRecord = fetchGoldenRecord(firmwareHash.getStationId());
                String goldenHash = goldenRecord.goldenHash();
                String manufacturerId = goldenRecord.manufacturerId();
                String manufacturerSignature = goldenRecord.manufacturerSignature();
                boolean signatureVerified = verifyManufacturerSignature(
                    goldenHash,
                    manufacturerSignature,
                    manufacturerId
                );

                FirmwareHash.VerificationStatus status = determineStatus(
                    firmwareHash.getReportedHash(),
                    goldenHash,
                    signatureVerified
                );

                log.info("[Trust] Verification result — stationId={} status={}",
                    firmwareHash.getStationId(), status);

                FirmwareHash output = FirmwareHash.builder()
                    .stationId(firmwareHash.getStationId())
                    .reportedHash(firmwareHash.getReportedHash())
                    .goldenHash(goldenHash)
                    .firmwareVersion(firmwareHash.getFirmwareVersion())
                    .manufacturerId(manufacturerId)
                    .manufacturerSignature(manufacturerSignature)
                    .signatureVerified(signatureVerified)
                    .reportedAt(observedAt)
                    .status(status)
                    .build();

                long latencyMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
                TrustEvidence evidence = new TrustEvidence(
                    firmwareHash.getStationId(),
                    mapVerdict(status),
                    firmwareHash.getReportedHash(),
                    goldenHash,
                    manufacturerId,
                    manufacturerSignature,
                    signatureVerified,
                    contractAddress,
                    null,
                    TrustEvidence.RpcStatus.REACHABLE,
                    observedAt,
                    buildRationale(status, firmwareHash.getReportedHash(), goldenHash, signatureVerified),
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
                    .manufacturerId(firmwareHash.getManufacturerId())
                    .manufacturerSignature(firmwareHash.getManufacturerSignature())
                    .signatureVerified(Boolean.FALSE)
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
                GoldenRecord parsed = decodeGoldenRecord(goldenHash);
                if (parsed.goldenHash() != null
                    && !parsed.goldenHash().isBlank()
                    && parsed.manufacturerSignature() != null
                    && !parsed.manufacturerSignature().isBlank()) {
                    var signedReceipt = firmwareRegistry
                        .registerSignedGoldenHash(
                            stationId,
                            parsed.goldenHash(),
                            parsed.manufacturerSignature(),
                            parsed.manufacturerId() == null ? trustedManufacturerId : parsed.manufacturerId())
                        .send();
                    log.info("[Trust] Signed hash registered — txHash={}", signedReceipt.getTransactionHash());
                    return signedReceipt.getTransactionHash();
                }

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
    public CompletableFuture<String> registerSignedGoldenHash(String stationId,
                                                              String goldenHash,
                                                              String manufacturerSignature,
                                                              String manufacturerId) {
        log.info("[Trust] Registering signed golden hash — stationId={} manufacturerId={}",
            stationId, manufacturerId);

        return CompletableFuture.supplyAsync(() -> {
            try {
                var receipt = firmwareRegistry
                    .registerSignedGoldenHash(
                        stationId,
                        goldenHash,
                        manufacturerSignature,
                        (manufacturerId == null || manufacturerId.isBlank()) ? trustedManufacturerId : manufacturerId)
                    .send();
                log.info("[Trust] Signed hash registered — txHash={}", receipt.getTransactionHash());
                return receipt.getTransactionHash();
            } catch (Exception ex) {
                throw new RuntimeException("Failed to register signed golden hash for " + stationId, ex);
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

    private FirmwareHash.VerificationStatus determineStatus(String reported,
                                                            String onChain,
                                                            boolean signatureVerified) {
        if (reported == null || reported.isBlank()) {
            return FirmwareHash.VerificationStatus.UNKNOWN_STATION;
        }
        if (onChain == null || onChain.isBlank() || onChain.equals("0x")) {
            return FirmwareHash.VerificationStatus.UNKNOWN_STATION;
        }
        if (!signatureVerified) {
            return FirmwareHash.VerificationStatus.TAMPERED;
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
                                  String onChain,
                                  boolean signatureVerified) {
        if (status == FirmwareHash.VerificationStatus.VERIFIED) {
            return "Manufacturer signature verified and reported hash matches on-chain golden hash.";
        }
        if (status == FirmwareHash.VerificationStatus.TAMPERED) {
            if (!signatureVerified) {
                return "Golden hash signature verification failed using configured manufacturer public key.";
            }
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

    String encodeSignedGoldenRecord(String goldenHash,
                                    String manufacturerSignature,
                                    String manufacturerId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(GOLDEN_HASH_FIELD, goldenHash);
        root.put(SIGNATURE_FIELD, manufacturerSignature);
        root.put(MANUFACTURER_FIELD,
            (manufacturerId == null || manufacturerId.isBlank())
                ? trustedManufacturerId
                : manufacturerId);
        return root.toString();
    }

    private GoldenRecord decodeGoldenRecord(String onChainValue) {
        if (onChainValue == null || onChainValue.isBlank() || "0x".equalsIgnoreCase(onChainValue)) {
            return new GoldenRecord(null, null, null);
        }

        String trimmed = onChainValue.trim();
        if (!trimmed.startsWith("{")) {
            return new GoldenRecord(trimmed, null, trustedManufacturerId);
        }

        try {
            JsonNode root = MAPPER.readTree(trimmed);
            String goldenHash = textValue(root, GOLDEN_HASH_FIELD, "hash", "reportedHash");
            String signature = textValue(root, SIGNATURE_FIELD, "signature");
            String manufacturerId = textValue(root, MANUFACTURER_FIELD, "manufacturer");

            if (manufacturerId == null || manufacturerId.isBlank()) {
                manufacturerId = trustedManufacturerId;
            }

            return new GoldenRecord(goldenHash, signature, manufacturerId);
        } catch (Exception ex) {
            log.warn("[Trust] Unable to parse signed golden record payload. Falling back to legacy hash-only mode");
            return new GoldenRecord(trimmed, null, trustedManufacturerId);
        }
    }

    private GoldenRecord fetchGoldenRecord(String stationId) throws Exception {
        try {
            FirmwareRegistryContract.SignedGoldenRecord nativeRecord = firmwareRegistry
                .getSignedGoldenRecord(stationId)
                .send();

            if (nativeRecord != null
                && nativeRecord.goldenHash != null
                && !nativeRecord.goldenHash.isBlank()) {
                String manufacturerId = nativeRecord.manufacturerId;
                if (manufacturerId == null || manufacturerId.isBlank()) {
                    manufacturerId = trustedManufacturerId;
                }

                return new GoldenRecord(
                    nativeRecord.goldenHash,
                    nativeRecord.manufacturerSignature,
                    manufacturerId
                );
            }
        } catch (Exception nativeReadError) {
            log.debug("[Trust] Native signed-record lookup unavailable, falling back to legacy golden hash", nativeReadError);
        }

        String legacyValue = firmwareRegistry.getGoldenHash(stationId).send();
        return decodeGoldenRecord(legacyValue);
    }

    private boolean verifyManufacturerSignature(String goldenHash,
                                                String manufacturerSignature,
                                                String manufacturerId) {
        if (goldenHash == null || goldenHash.isBlank()) {
            return false;
        }

        if (manufacturerSignature == null || manufacturerSignature.isBlank()) {
            return false;
        }

        if (trustedManufacturerId != null
            && !trustedManufacturerId.isBlank()
            && manufacturerId != null
            && !manufacturerId.isBlank()
            && !trustedManufacturerId.equalsIgnoreCase(manufacturerId)) {
            return false;
        }

        if (manufacturerPublicKeyBase64 == null || manufacturerPublicKeyBase64.isBlank()) {
            return false;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(manufacturerPublicKeyBase64);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(goldenHash.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = Base64.getDecoder().decode(manufacturerSignature);
            return verifier.verify(signatureBytes);
        } catch (Exception ex) {
            log.warn("[Trust] Signature verification failed due to crypto error", ex);
            return false;
        }
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

    private record GoldenRecord(String goldenHash,
                                String manufacturerSignature,
                                String manufacturerId) {
    }
}
