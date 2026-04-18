package com.cybersecuals.gridgarrison.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcppHandshakeInterceptorTest {

    private final OcppHandshakeInterceptor interceptor = new OcppHandshakeInterceptor();

    @Test
    void acceptsHandshakeWhenProtocolAndCnMatchStationId() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            request("EV-Simulator-001", true, "EV-Simulator-001"),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            new TextWebSocketHandler(),
            attributes
        );

        assertTrue(accepted);
        assertEquals("EV-Simulator-001", attributes.get("stationId"));
        assertEquals("EV-Simulator-001", attributes.get("certCn"));
    }

    @Test
    void rejectsHandshakeWhenCertificateCnDoesNotMatchStationId() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            request("CS-999-EV", true, "EV-Simulator-001"),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            new TextWebSocketHandler(),
            attributes
        );

        assertFalse(accepted);
        assertEquals("CS-999-EV", attributes.get("stationId"));
        assertEquals("EV-Simulator-001", attributes.get("certCn"));
    }

    @Test
    void rejectsHandshakeWhenRequiredSubprotocolIsMissing() {
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            request("EV-Simulator-001", false, "EV-Simulator-001"),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            new TextWebSocketHandler(),
            attributes
        );

        assertFalse(accepted);
    }

    @Test
    void rejectsHandshakeWhenCertificateCommonNameIsMissing() {
        Map<String, Object> attributes = new HashMap<>();

        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ocpp/EV-Simulator-001");
        servletRequest.addHeader("Sec-WebSocket-Protocol", "ocpp2.0.1");
        servletRequest.setAttribute(
            "jakarta.servlet.request.X509Certificate",
            new X509Certificate[] { certificateWithSubject("OU=GridGarrison,O=GridGarrison") }
        );

        boolean accepted = interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            new TextWebSocketHandler(),
            attributes
        );

        assertFalse(accepted);
    }

    private ServletServerHttpRequest request(String stationId, boolean includeProtocol, String certificateCn) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ocpp/" + stationId);
        if (includeProtocol) {
            servletRequest.addHeader("Sec-WebSocket-Protocol", "ocpp2.0.1");
        }
        if (certificateCn != null && !certificateCn.isBlank()) {
            servletRequest.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[] { certificate(certificateCn) }
            );
        }
        return new ServletServerHttpRequest(servletRequest);
    }

    private X509Certificate certificate(String commonName) {
        return certificateWithSubject("CN=" + commonName + ",OU=GridGarrison,O=GridGarrison");
    }

    private X509Certificate certificateWithSubject(String subjectDn) {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectX500Principal()).thenReturn(
            new javax.security.auth.x500.X500Principal(subjectDn)
        );
        return certificate;
    }
}
