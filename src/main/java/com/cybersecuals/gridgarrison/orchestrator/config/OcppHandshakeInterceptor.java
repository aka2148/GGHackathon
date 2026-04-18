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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Map<String, Set<String>> stationCnAliases;

    OcppHandshakeInterceptor() {
        this("");
    }

    OcppHandshakeInterceptor(String stationCnAliasesRaw) {
        this.stationCnAliases = parseStationCnAliases(stationCnAliasesRaw);
    }

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
            if (!stationId.equals(certCn) && !isAllowedAlias(stationId, certCn)) {
                log.warn("[OCPP] Handshake rejected: stationId/certificate CN mismatch stationId={} certCn={} uri={}",
                    stationId, certCn, request.getURI());
                return false;
            }

            if (!stationId.equals(certCn)) {
                log.info("[OCPP] Handshake accepted via station/CN alias mapping stationId={} certCn={} uri={}",
                    stationId, certCn, request.getURI());
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

    private boolean isAllowedAlias(String stationId, String certCn) {
        Set<String> aliases = stationCnAliases.get(stationId);
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        return aliases.contains(certCn);
    }

    private Map<String, Set<String>> parseStationCnAliases(String raw) {
        Map<String, Set<String>> aliases = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return aliases;
        }

        String[] pairs = raw.split("[;,]");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank() || !pair.contains("=")) {
                continue;
            }

            String[] stationToCn = pair.split("=", 2);
            String stationId = stationToCn[0] == null ? "" : stationToCn[0].trim();
            String certCnValue = stationToCn[1] == null ? "" : stationToCn[1].trim();
            if (stationId.isBlank() || certCnValue.isBlank()) {
                continue;
            }

            Set<String> cns = aliases.computeIfAbsent(stationId, key -> new HashSet<>());
            Arrays.stream(certCnValue.split("\\|"))
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .forEach(cns::add);
        }

        return aliases;
    }
}
