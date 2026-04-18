package com.cybersecuals.gridgarrison.orchestrator.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iso15118PolicyValidatorTest {

    @Test
    void returnsNoViolationForEimAuthorizationMode() {
        String payload = "{\"eventType\":\"Started\",\"authorizationMode\":\"EIM\",\"transactionId\":\"TXN-1\"}";

        String violation = Iso15118PolicyValidator.validateTransactionPayload(payload);

        assertNull(violation);
    }

    @Test
    void returnsViolationForPlugAndChargeWithoutContractCertificate() {
        String payload = "{\"eventType\":\"Started\",\"authorizationMode\":\"PlugAndCharge\",\"transactionId\":\"TXN-2\"}";

        String violation = Iso15118PolicyValidator.validateTransactionPayload(payload);

        assertNotNull(violation);
        assertTrue(violation.contains("contractCertificateId"));
    }

    @Test
    void returnsViolationForUnknownAuthorizationMode() {
        String payload = "{\"eventType\":\"Started\",\"authorizationMode\":\"CardSwipe\",\"transactionId\":\"TXN-3\"}";

        String violation = Iso15118PolicyValidator.validateTransactionPayload(payload);

        assertNotNull(violation);
        assertTrue(violation.contains("Expected EIM or PlugAndCharge"));
    }
}
