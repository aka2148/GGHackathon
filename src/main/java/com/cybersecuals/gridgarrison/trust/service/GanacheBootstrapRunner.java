package com.cybersecuals.gridgarrison.trust.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "gridgarrison.blockchain.bootstrap", name = "enabled", havingValue = "true")
class GanacheBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GanacheBootstrapRunner.class);

    private final BlockchainService blockchainService;
    private final BlockchainServiceImpl blockchainServiceImpl;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${gridgarrison.blockchain.contract-address:}")
    private String configuredContractAddress;

    @Value("#{${gridgarrison.blockchain.bootstrap.seed-golden-hashes:{}}}")
    private Map<String, String> seedGoldenHashes;

    @Value("${gridgarrison.trust.manufacturer.id:ACME-MFG}")
    private String manufacturerId;

    @Value("${gridgarrison.trust.manufacturer.private-key-base64:}")
    private String manufacturerPrivateKeyBase64;

    @SuppressWarnings("unused")
    GanacheBootstrapRunner(BlockchainService blockchainService,
                           BlockchainServiceImpl blockchainServiceImpl,
                           ApplicationEventPublisher eventPublisher) {
        this.blockchainService = blockchainService;
        this.blockchainServiceImpl = blockchainServiceImpl;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void run(String... args) {
        String contractAddress = configuredContractAddress;
        if (isUnset(contractAddress)) {
            try {
                contractAddress = blockchainServiceImpl.deployContract();
                log.info("[Bootstrap] Deployed contract address={}", contractAddress);
                log.info("[Bootstrap] Set GG_CONTRACT_ADDR={} for future runs", contractAddress);
            } catch (Exception ex) {
                log.warn("[Bootstrap] Contract deployment skipped — using configured zero address", ex);
                return;
            }
        } else {
            blockchainServiceImpl.bindContract(contractAddress);
            log.info("[Bootstrap] Using configured contract address={}", contractAddress);
        }

        if (seedGoldenHashes == null || seedGoldenHashes.isEmpty()) {
            log.info("[Bootstrap] No seed hashes configured");
            return;
        }

        seedGoldenHashes.forEach((stationId, hash) -> {
            try {
                eventPublisher.publishEvent(new ManufactureHashGeneratedEvent(
                    stationId,
                    "Manufacture stage generated golden hash " + hash
                ));

                String manufacturerSignature = signGoldenHash(hash);
                eventPublisher.publishEvent(new GoldenHashSignedEvent(
                    stationId,
                    "Manufacturer " + manufacturerId + " signed golden hash"
                ));

                String txHash = blockchainService.registerSignedGoldenHash(
                    stationId,
                    hash,
                    manufacturerSignature,
                    manufacturerId
                ).join();
                log.info("[Bootstrap] Seeded stationId={} txHash={}", stationId, txHash);
                eventPublisher.publishEvent(new SignedGoldenHashStoredOnChainEvent(
                    stationId,
                    "Signed golden hash stored on-chain txHash=" + txHash,
                    txHash
                ));
            } catch (Exception ex) {
                log.error("[Bootstrap] Failed seeding stationId={}", stationId, ex);
            }
        });

        try {
            String txHash = blockchainService
                .recordSessionEvent("BOOTSTRAP", "SESSION-BOOTSTRAP", "BOOTSTRAP")
                .join();
            log.info("[Bootstrap] Session audit marker txHash={}", txHash);
        } catch (Exception ex) {
            log.warn("[Bootstrap] Session audit marker skipped", ex);
        }
    }

    private boolean isUnset(String value) {
        return value == null
            || value.isBlank()
            || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(value.trim());
    }

    private String signGoldenHash(String goldenHash) {
        if (manufacturerPrivateKeyBase64 == null || manufacturerPrivateKeyBase64.isBlank()) {
            throw new IllegalStateException("Manufacturer private key is not configured for bootstrap signing");
        }

        try {
            byte[] privateBytes = Base64.getDecoder().decode(manufacturerPrivateKeyBase64);
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(goldenHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign seeded golden hash", ex);
        }
    }
}