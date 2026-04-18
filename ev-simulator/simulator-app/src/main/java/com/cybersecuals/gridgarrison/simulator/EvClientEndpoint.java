package com.cybersecuals.gridgarrison.simulator;

import lombok.extern.slf4j.Slf4j;

import javax.websocket.*;

/**
 * WebSocket endpoint callback handler for the EV simulator.
 * Routes events back to the EvWebSocketClient.
 */
@Slf4j
@ClientEndpoint(subprotocols = "ocpp2.0.1")
public class EvClientEndpoint {

    private final EvWebSocketClient client;

    public EvClientEndpoint(EvWebSocketClient client) {
        this.client = client;
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("🔗 WebSocket session opened: {}", session.getId());
        client.onOpen(session);
    }

    @OnMessage
    public void onMessage(String message) {
        log.debug("📨 Message received: {}", message);
        client.onMessage(message);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("❌ WebSocket error on session {}", session.getId(), throwable);
        client.onError(throwable);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("🔌 WebSocket closed: {} - {}", session.getId(), reason);
        client.onClose();
    }
}
