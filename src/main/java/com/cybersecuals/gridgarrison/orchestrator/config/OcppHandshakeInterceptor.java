package com.cybersecuals.gridgarrison.orchestrator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * Validates the OCPP 2.0.1 WebSocket handshake.
 *
 * Per OCPP 2.0.1 spec §3.1 the client MUST send:
 *   Sec-WebSocket-Protocol: ocpp2.0.1
 *
 * Connections advertising only older protocol versions are rejected here
 * before the handler is invoked.
 */
@Slf4j
class OcppHandshakeInterceptor implements HandshakeInterceptor {

    private static final String OCPP_201 = "ocpp2.0.1";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        List<String> protocols = request.getHeaders()
            .getOrDefault("Sec-WebSocket-Protocol", List.of());

        if (!protocols.contains(OCPP_201)) {
            log.warn("[OCPP] Handshake rejected: missing required subprotocol '{}' uri={} advertised={}",
                OCPP_201, request.getURI(), protocols);
            // Reject — station must negotiate ocpp2.0.1
            return false;
        }

        // Stash stationId from path for the handler
        String path = request.getURI().getPath();
        String stationId = path.substring(path.lastIndexOf('/') + 1);
        if (stationId.isBlank()) {
            log.warn("[OCPP] Handshake rejected: missing stationId in path uri={}", request.getURI());
            return false;
        }
        attributes.put("stationId", stationId);

        X509Certificate[] certificates = extractCertificates(request);
        if (certificates != null && certificates.length > 0) {
            String certCn = extractCommonName(certificates[0]);
            if (certCn == null || certCn.isBlank()) {
                log.warn("[OCPP] Handshake rejected: client certificate CN missing stationId={} uri={}",
                    stationId, request.getURI());
                return false;
            }
            attributes.put("certCn", certCn);
            if (!stationId.equals(certCn)) {
                log.warn("[OCPP] Handshake rejected: stationId/certificate CN mismatch stationId={} certCn={} uri={}",
                    stationId, certCn, request.getURI());
                return false;
            }
        }

        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                                ServerHttpResponse response,
                                WebSocketHandler wsHandler,
                                Exception exception) {
        // no-op
    }

    private X509Certificate[] extractCertificates(ServerHttpRequest request) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return null;
        }

        Object jakartaCertAttr = servletRequest.getServletRequest()
            .getAttribute("jakarta.servlet.request.X509Certificate");
        if (jakartaCertAttr instanceof X509Certificate[] jakartaCerts) {
            return jakartaCerts;
        }

        Object javaxCertAttr = servletRequest.getServletRequest()
            .getAttribute("javax.servlet.request.X509Certificate");
        if (javaxCertAttr instanceof X509Certificate[] javaxCerts) {
            return javaxCerts;
        }

        return null;
    }

    private String extractCommonName(X509Certificate certificate) {
        try {
            LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    Object value = rdn.getValue();
                    return value == null ? null : value.toString();
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }
}
