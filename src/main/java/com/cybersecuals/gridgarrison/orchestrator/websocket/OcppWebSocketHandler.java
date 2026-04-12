package com.cybersecuals.gridgarrison.orchestrator.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Public-API component of the {@code orchestrator} module.
 *
 * Handles the raw OCPP 2.0.1 message lifecycle over WSS:
 *  - BootNotificationRequest / Response
 *  - HeartbeatRequest / Response
 *  - TransactionEvent (replaces StartTransaction / StopTransaction in 2.0.1)
 *  - FirmwareStatusNotification
 *  - SecurityEventNotification
 *
 * Messages are deserialized from OCPP JSON (SRPC format) and published
 * as Spring application events so the {@code trust} and {@code watchdog}
 * modules can react without direct coupling.
 */
@Slf4j
@Component
public class OcppWebSocketHandler extends TextWebSocketHandler {

    private final ApplicationEventPublisher eventPublisher;

    public OcppWebSocketHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String stationId = (String) session.getAttributes().get("stationId");
        log.info("[OCPP] Station connected — stationId={} sessionId={}",
            stationId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String stationId = (String) session.getAttributes().get("stationId");
        log.info("[OCPP] Station disconnected — stationId={} status={}", stationId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[OCPP] Transport error — sessionId={}", session.getId(), exception);
    }

    // -------------------------------------------------------------------------
    // Message dispatch
    // -------------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String stationId = (String) session.getAttributes().get("stationId");
        String payload   = message.getPayload();

        log.debug("[OCPP] ← {} | {}", stationId, payload);

        // OCPP SRPC envelope: [messageTypeId, messageId, action, payload]
        // messageTypeId: 2 = CALL, 3 = CALLRESULT, 4 = CALLERROR
        OcppMessage ocppMessage = OcppMessage.parse(stationId, payload);

        switch (ocppMessage.action()) {
            case "BootNotification"         -> handleBootNotification(session, ocppMessage);
            case "Heartbeat"                -> handleHeartbeat(session, ocppMessage);
            case "TransactionEvent"         -> handleTransactionEvent(session, ocppMessage);
            case "FirmwareStatusNotification" -> handleFirmwareStatus(session, ocppMessage);
            case "SecurityEventNotification"  -> handleSecurityEvent(session, ocppMessage);
            default -> log.warn("[OCPP] Unhandled action={} stationId={}", ocppMessage.action(), stationId);
        }
    }

    // -------------------------------------------------------------------------
    // Action handlers — package-private, wired internally
    // -------------------------------------------------------------------------

    private void handleBootNotification(WebSocketSession session, OcppMessage msg) throws Exception {
        log.info("[OCPP] BootNotification from stationId={}", msg.stationId());
        // TODO: validate station certificate CN against stationId
        // Publish event for watchdog to register digital twin
        eventPublisher.publishEvent(new StationBootEvent(msg.stationId(), msg.rawPayload()));
        // Respond: Accepted with current UTC timestamp
        String response = OcppResponse.bootNotificationAccepted(msg.messageId());
        session.sendMessage(new TextMessage(response));
    }

    private void handleHeartbeat(WebSocketSession session, OcppMessage msg) throws Exception {
        String response = OcppResponse.heartbeat(msg.messageId());
        session.sendMessage(new TextMessage(response));
    }

    private void handleTransactionEvent(WebSocketSession session, OcppMessage msg) {
        log.info("[OCPP] TransactionEvent stationId={}", msg.stationId());
        eventPublisher.publishEvent(new TransactionEvent(msg.stationId(), msg.rawPayload()));
    }

    private void handleFirmwareStatus(WebSocketSession session, OcppMessage msg) {
        log.info("[OCPP] FirmwareStatusNotification stationId={}", msg.stationId());
        // trust module listens for this event to trigger Golden Hash verification
        eventPublisher.publishEvent(new FirmwareStatusEvent(msg.stationId(), msg.rawPayload()));
    }

    private void handleSecurityEvent(WebSocketSession session, OcppMessage msg) {
        log.warn("[OCPP] SecurityEventNotification stationId={}", msg.stationId());
        eventPublisher.publishEvent(new SecurityAlertEvent(msg.stationId(), msg.rawPayload()));
    }
}
