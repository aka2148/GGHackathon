package com.cybersecuals.gridgarrison.trust.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final EscrowService escrowService;
    private final EscrowAnomalyListener anomalyListener;

    @Override
    public String startSession(String stationId) {

        try {
            String chargerWallet = "0x1111111111111111111111111111111111111111";

            // IMPORTANT: must be valid 32-byte hex
            String goldenHash = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

            int targetSoc = 80;
            long timeout = 300;

            log.info("🚀 Deploying escrow...");

            String escrowAddress = escrowService.deployEscrow(
                    stationId,
                    chargerWallet,
                    goldenHash,
                    targetSoc,
                    timeout
            ).join();

            log.info("✅ Escrow deployed at {}", escrowAddress);

            // 🔥 THIS FIXES YOUR ENTIRE PROBLEM
            anomalyListener.registerEscrow(stationId, escrowAddress);

            return escrowAddress;

        } catch (Exception e) {
            log.error("❌ Session start failed", e);
            throw new RuntimeException(e);
        }
    }
}