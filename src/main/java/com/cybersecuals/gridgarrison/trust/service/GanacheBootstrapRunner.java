package com.cybersecuals.gridgarrison.trust.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "gridgarrison.blockchain.bootstrap", name = "enabled", havingValue = "true")
class GanacheBootstrapRunner implements CommandLineRunner {

    private final BlockchainService blockchainService;
    private final BlockchainServiceImpl blockchainServiceImpl;

    @Value("${gridgarrison.blockchain.contract-address:}")
    private String configuredContractAddress;

    @Value("#{${gridgarrison.blockchain.bootstrap.seed-golden-hashes:{}}}")
    private Map<String, String> seedGoldenHashes;

    GanacheBootstrapRunner(BlockchainService blockchainService,
                           BlockchainServiceImpl blockchainServiceImpl) {
        this.blockchainService = blockchainService;
        this.blockchainServiceImpl = blockchainServiceImpl;
    }

    @Override
    public void run(String... args) {
        String contractAddress = configuredContractAddress;
        if (isUnset(contractAddress)) {
            contractAddress = blockchainServiceImpl.deployContract();
            log.info("[Bootstrap] Deployed contract address={}", contractAddress);
            log.info("[Bootstrap] Set GG_CONTRACT_ADDR={} for future runs", contractAddress);
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
                String txHash = blockchainService.registerGoldenHash(stationId, hash).join();
                log.info("[Bootstrap] Seeded stationId={} txHash={}", stationId, txHash);
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
            log.error("[Bootstrap] Failed writing session audit marker", ex);
        }
    }

    private boolean isUnset(String value) {
        return value == null
            || value.isBlank()
            || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(value.trim());
    }
}