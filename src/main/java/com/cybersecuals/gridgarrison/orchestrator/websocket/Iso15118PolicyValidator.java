package com.cybersecuals.gridgarrison.orchestrator.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Iso15118PolicyValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_MODE_EIM = "EIM";
    private static final String AUTH_MODE_PNC = "PlugAndCharge";

    private Iso15118PolicyValidator() {
    }

    static String validateTransactionPayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawPayload);
        } catch (Exception ignored) {
            return null;
        }

        String authorizationMode = textValue(payload, "authorizationMode", "authMode");
        if (authorizationMode == null || authorizationMode.isBlank()) {
            return null;
        }

        if (!AUTH_MODE_EIM.equals(authorizationMode) && !AUTH_MODE_PNC.equals(authorizationMode)) {
            return "Unknown ISO-15118 authorizationMode='" + authorizationMode + "'. Expected EIM or PlugAndCharge.";
        }

        if (AUTH_MODE_PNC.equals(authorizationMode)) {
            String contractCertificateId = textValue(payload, "contractCertificateId", "contractCertId");
            if (contractCertificateId == null || contractCertificateId.isBlank()) {
                return "ISO-15118 PlugAndCharge requires contractCertificateId in TransactionEvent payload.";
            }
        }

        return null;
    }

    private static String textValue(JsonNode node, String... keys) {
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
}
