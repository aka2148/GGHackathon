package com.cybersecuals.gridgarrison.shared.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO representing an active or completed EV charging session.
 * Flows across module boundaries — keep this stable and backward-compatible.
 */
@Value
@Builder
public class ChargingSession {

    /** Globally unique session identifier (maps to OCPP transactionId). */
    UUID sessionId;

    /** Station identity — typically the EVSE serial or OCPP stationId. */
    String stationId;

    /** EV identity — DID or certificate CN extracted during mTLS handshake. */
    String evIdentity;

    /** Energy delivered in kWh (updated incrementally via MeterValues). */
    double energyDeliveredKwh;

    /** Session start timestamp (UTC). */
    Instant startedAt;

    /** Session end timestamp (UTC); null while session is still active. */
    Instant endedAt;

    /** Current session lifecycle state. */
    SessionState state;

    public enum SessionState {
        PENDING,
        ACTIVE,
        SUSPENDED_EV,
        SUSPENDED_EVSE,
        FINISHING,
        COMPLETED,
        FAULTED
    }
}
