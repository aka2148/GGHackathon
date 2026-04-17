package com.cybersecuals.gridgarrison.orchestrator.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight wrapper around an OCPP SRPC JSON frame.
 * Package-private — implementation detail of the orchestrator module.
 *
 * SRPC format: [messageTypeId, messageId, action?, payload]
 */
record OcppMessage(int messageTypeId, String messageId, String action,
                   String rawPayload, String stationId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static OcppMessage parse(String stationId, String json) {
        try {
            JsonNode array = MAPPER.readTree(json);
            int    typeId  = array.get(0).asInt();
            String msgId   = array.get(1).asText();
            // CALL (2) has action at index 2; CALLRESULT (3) / CALLERROR (4) do not
            String action  = (typeId == 2) ? array.get(2).asText() : "";
            String payload = (typeId == 2)
                ? array.get(3).toString()
                : array.get(2).toString();
            return new OcppMessage(typeId, msgId, action, payload, stationId);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalArgumentException("Malformed OCPP frame: " + json, e);
        }
    }
}

/** Utility class for building OCPP CALLRESULT frames. Package-private. */
class OcppResponse {

    private OcppResponse() {}

    static String bootNotificationAccepted(String messageId) {
        return """
            [3, "%s", {"currentTime": "%s", "interval": 300, "status": "Accepted"}]
            """.formatted(messageId, java.time.Instant.now().toString()).strip();
    }

    static String heartbeat(String messageId) {
        return """
            [3, "%s", {"currentTime": "%s"}]
            """.formatted(messageId, java.time.Instant.now().toString()).strip();
    }
}

// ---------------------------------------------------------------------------
// Internal domain events published to the Spring context.
// Other modules subscribe via @ApplicationModuleListener — no direct coupling.
// ---------------------------------------------------------------------------
