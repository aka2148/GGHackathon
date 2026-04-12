package com.cybersecuals.gridgarrison.orchestrator.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

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
            // Reject — station must negotiate ocpp2.0.1
            return false;
        }

        // Stash stationId from path for the handler
        String path = request.getURI().getPath();
        String stationId = path.substring(path.lastIndexOf('/') + 1);
        attributes.put("stationId", stationId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                                ServerHttpResponse response,
                                WebSocketHandler wsHandler,
                                Exception exception) {
        // no-op
    }
}
