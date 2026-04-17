package com.cybersecuals.gridgarrison.trust.service;

import java.time.Instant;

/**
 * Result payload for runtime signed golden-hash registration.
 */
public record SignedGoldenRegistrationResult(
    String stationId,
    String goldenHash,
    String manufacturerId,
    String manufacturerSignature,
    String txHash,
    Instant observedAt,
    boolean overwritten
) {
}
