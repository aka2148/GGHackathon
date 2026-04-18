package com.cybersecuals.gridgarrison.trust.service;

import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
class EscrowIntentStore {

    private final Map<String, EscrowIntent> intentsByStation = new ConcurrentHashMap<>();

    EscrowIntent upsert(String stationId,
                        BigInteger holdAmountWei,
                        Integer targetSoc,
                        Long timeoutSeconds,
                        String chargerWallet) {
        String resolvedStationId = normalizeStationId(stationId);
        EscrowIntent next = new EscrowIntent(
            resolvedStationId,
            holdAmountWei,
            targetSoc,
            timeoutSeconds,
            chargerWallet,
            Instant.now()
        );
        intentsByStation.put(resolvedStationId, next);
        return next;
    }

    Optional<EscrowIntent> get(String stationId) {
        return Optional.ofNullable(intentsByStation.get(normalizeStationId(stationId)));
    }

    Optional<EscrowIntent> clear(String stationId) {
        return Optional.ofNullable(intentsByStation.remove(normalizeStationId(stationId)));
    }

    private String normalizeStationId(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return "CS-101";
        }
        return stationId;
    }

    record EscrowIntent(
        String stationId,
        BigInteger holdAmountWei,
        Integer targetSoc,
        Long timeoutSeconds,
        String chargerWallet,
        Instant createdAt
    ) {
    }
}
