package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;

/**
 * Combined trust verification output used by listeners and visualizer plumbing.
 */
public record TrustVerificationResult(FirmwareHash firmwareHash, TrustEvidence evidence) {
}