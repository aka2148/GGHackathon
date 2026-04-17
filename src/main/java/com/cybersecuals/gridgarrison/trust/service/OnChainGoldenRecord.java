package com.cybersecuals.gridgarrison.trust.service;

import java.time.Instant;

/**
 * Read-only snapshot of the on-chain signed golden record for a station.
 */
public record OnChainGoldenRecord(
    String stationId,
    String goldenHash,
    String manufacturerId,
    String manufacturerSignature,
    String contractAddress,
    Instant observedAt
) {
}