package com.cybersecuals.gridgarrison.orchestrator.config;

import com.cybersecuals.gridgarrison.orchestrator.websocket.OcppWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket configuration for OCPP 2.0.1 over WSS.
 *
 * OCPP 2.0.1 mandates the sub-protocol "ocpp2.0.1".
 * Stations connect to: wss://<host>/ocpp/{stationId}
 *
 * TLS termination (and mTLS client-cert validation) is enforced at the
 * {@link MtlsSecurityConfig} layer — by the time a frame reaches
 * {@link OcppWebSocketHandler} the peer identity is already authenticated.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final OcppWebSocketHandler ocppWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry
            .addHandler(ocppWebSocketHandler, "/ocpp/{stationId}")
            // OCPP 2.0.1 required sub-protocol header
            .setAllowedOriginPatterns("*")
            .addInterceptors(new OcppHandshakeInterceptor());
    }
}
