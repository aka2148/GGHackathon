package com.cybersecuals.gridgarrison.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket client that connects to GridGarrison as a dummy EV.
 * Streams OCPP 2.0.1 events over a persistent connection.
 *
 * OCPP SRPC format: [messageTypeId, messageId, action, payload]
 * messageTypeId: 2 = CALL, 3 = CALLRESULT, 4 = CALLERROR
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ev.simulator.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EvWebSocketClient {

    @Value("${ev.simulator.station-id}")
    private String stationId;

    @Value("${ev.simulator.gateway-uri}")
    private String gatewayUri;

    @Value("${ev.simulator.reconnect.enabled:true}")
    private boolean reconnectEnabled;

    @Value("${ev.simulator.reconnect.max-attempts:5}")
    private int reconnectMaxAttempts;

    @Value("${ev.simulator.reconnect.initial-delay-ms:1000}")
    private long reconnectInitialDelayMs;

    @Value("${ev.simulator.reconnect.max-delay-ms:30000}")
    private long reconnectMaxDelayMs;

    @Value("${ev.simulator.tls.enabled:false}")
    private boolean tlsEnabled;

    @Value("${ev.simulator.tls.key-store-path:}")
    private String tlsKeyStorePath;

    @Value("${ev.simulator.tls.key-store-password:}")
    private String tlsKeyStorePassword;

    @Value("${ev.simulator.tls.key-store-type:PKCS12}")
    private String tlsKeyStoreType;

    @Value("${ev.simulator.tls.trust-store-path:}")
    private String tlsTrustStorePath;

    @Value("${ev.simulator.tls.trust-store-password:}")
    private String tlsTrustStorePassword;

    @Value("${ev.simulator.tls.trust-store-type:PKCS12}")
    private String tlsTrustStoreType;

    private Session session;
    private final EvTrustVerificationClient verificationClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicReference<String> lastFirmwareHash = new AtomicReference<>();

    /**
     * Initiates the WebSocket connection to GridGarrison.
     */
    @ConditionalOnProperty(name = "ev.simulator.enabled", havingValue = "true", matchIfMissing = true)
    public synchronized void connect() throws Exception {
        // Build URI: wss://host:port/ocpp/{stationId}
        String endpoint = gatewayUri.replace("{stationId}", stationId);
        WebSocketContainer container = resolveWebSocketContainer(endpoint);

        log.info("🔗 Connecting to {} with stationId={}", endpoint, stationId);

        try {
            session = container.connectToServer(
                new EvClientEndpoint(this),
                new URI(endpoint)
            );
            log.info("✅ Connected to GridGarrison");
        } catch (Exception e) {
            log.error("❌ Connection failed", e);
            throw e;
        }
    }

    private WebSocketContainer resolveWebSocketContainer(String endpoint) throws Exception {
        if (!endpoint.startsWith("wss://")) {
            return ContainerProvider.getWebSocketContainer();
        }

        ClientManager clientManager = ClientManager.createClient();
        if (!tlsEnabled) {
            log.warn("Using wss:// endpoint with default JVM SSL context (ev.simulator.tls.enabled=false)");
            return clientManager;
        }

        SSLContext sslContext = buildSslContext();
        SSLContext.setDefault(sslContext);
        log.info("Custom TLS context configured for simulator WebSocket client");
        return clientManager;
    }

    private SSLContext buildSslContext() throws Exception {
        KeyStore keyStore = loadKeyStore(tlsKeyStorePath, tlsKeyStoreType, tlsKeyStorePassword);
        KeyStore trustStore = loadKeyStore(tlsTrustStorePath, tlsTrustStoreType, tlsTrustStorePassword);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        char[] keyPassword = safePassword(tlsKeyStorePassword);
        keyManagerFactory.init(keyStore, keyPassword);

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private KeyStore loadKeyStore(String path, String type, String password) throws Exception {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("TLS store path is required when ev.simulator.tls.enabled=true");
        }

        KeyStore keyStore = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
        try (InputStream inputStream = openStoreStream(path)) {
            keyStore.load(inputStream, safePassword(password));
        }
        return keyStore;
    }

    private InputStream openStoreStream(String path) throws Exception {
        if (path.startsWith("classpath:")) {
            String classpathLocation = path.substring("classpath:".length());
            InputStream stream = getClass().getResourceAsStream(
                classpathLocation.startsWith("/") ? classpathLocation : "/" + classpathLocation
            );
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + path);
            }
            return stream;
        }
        return new FileInputStream(path);
    }

    private char[] safePassword(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    /**
     * Tries to connect with exponential backoff.
     */
    public void connectWithBackoff() {
        if (session != null && session.isOpen()) {
            return;
        }

        int attempt = 0;
        long delayMs = Math.max(100, reconnectInitialDelayMs);

        while (attempt < reconnectMaxAttempts) {
            attempt++;
            try {
                log.info("Reconnect attempt {}/{} in {} ms", attempt, reconnectMaxAttempts, delayMs);
                Thread.sleep(delayMs);
                connect();
                return;
            } catch (Exception e) {
                log.warn("Reconnect attempt {}/{} failed", attempt, reconnectMaxAttempts, e);
                delayMs = Math.min(delayMs * 2, Math.max(100, reconnectMaxDelayMs));
            }
        }

        log.error("Reconnect exhausted after {} attempts", reconnectMaxAttempts);
    }

    private void triggerReconnectAsync(String reason) {
        if (!reconnectEnabled) {
            return;
        }
        if (session != null && session.isOpen()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        Thread reconnectThread = new Thread(() -> {
            try {
                log.info("Triggering reconnect flow due to {}", reason);
                connectWithBackoff();
            } finally {
                reconnecting.set(false);
            }
        }, "ev-reconnect-thread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * Sends a Boot Notification to announce the EV / station.
     */
    public void sendBootNotification() throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session not open, skipping Boot");
            return;
        }

        // OCPP BootNotification request
        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("chargingStation", stationId);
        payloadMap.put("reason", "PowerUp");
        payloadMap.put("firmwareVersion", "1.0.0");
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"BootNotification\", %s]",
            UUID.randomUUID().toString(),
            payload
        );

        log.debug("📤 Boot: {}", ocppFrame);
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Sends a Heartbeat every heartbeat interval.
     */
    @Scheduled(fixedDelayString = "${ev.simulator.heartbeat-interval-ms:30000}")
    public synchronized void sendHeartbeat() throws Exception {
        if (session == null || !session.isOpen()) {
            log.debug("Session not open, skipping Heartbeat");
            return;
        }

        String ocppFrame = String.format(
            "[2, \"%s\", \"Heartbeat\", {}]",
            UUID.randomUUID().toString()
        );

        log.debug("💓 Heartbeat sent");
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (START).
     */
    public void sendTransactionStart(String transactionId) throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session not open, skipping Transaction");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Started");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("timestamp", System.currentTimeMillis());
        payloadMap.put("meterStart", 0.0);
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID().toString(),
            payload
        );

        log.info("🔌 Transaction START: {}", transactionId);
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (METER_UPDATE).
     */
    public void sendMeterUpdate(String transactionId, double kWh) throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session not open, skipping Meter");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Updated");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("meterValue", kWh);
        payloadMap.put("timestamp", System.currentTimeMillis());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID().toString(),
            payload
        );

        log.debug("⚡ Meter update: {} kWh", kWh);
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (END).
     */
    public void sendTransactionEnd(String transactionId, double totalKWh) throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session not open, skipping Transaction End");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Ended");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("meterStop", totalKWh);
        payloadMap.put("timestamp", System.currentTimeMillis());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID().toString(),
            payload
        );

        log.info("🛑 Transaction END: {} (total: {} kWh)", transactionId, totalKWh);
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Sends a FirmwareStatusNotification.
     */
    public void sendFirmwareStatus(String status, String hash) throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session not open, skipping Firmware Status");
            return;
        }

        if (hash != null && !hash.isBlank()) {
            lastFirmwareHash.set(hash);
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("status_field", status);
        payloadMap.put("firmwareHash", hash);
        payloadMap.put("receivedAt", Instant.now().toString());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"FirmwareStatusNotification\", %s]",
            UUID.randomUUID().toString(),
            payload
        );

        log.info("🔐 Firmware Status: {} | hash={}", status, hash);
        session.getBasicRemote().sendText(ocppFrame);
    }

    /**
     * Closes the WebSocket session.
     */
    public void disconnect() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
            log.info("🔌 Disconnected from GridGarrison");
        }
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public String getStationId() {
        return stationId;
    }

    public String getLastFirmwareHash() {
        return lastFirmwareHash.get();
    }

    // Internal callback for endpoint
    void onOpen(Session session) {
        this.session = session;
        log.info("✅ WebSocket opened");
        try {
            sendBootNotification();
            verificationClient.verifyAfterConnectAsync(stationId, lastFirmwareHash.get());
        } catch (Exception e) {
            log.error("Failed to send boot notification", e);
        }
    }

    void onMessage(String message) {
        log.debug("📥 Received: {}", message);
        // OCPP CALLRESULT / CALLERROR handling can go here
    }

    void onError(Throwable throwable) {
        log.error("❌ WebSocket error", throwable);
        triggerReconnectAsync("websocket error");
    }

    void onClose() {
        log.info("🔌 WebSocket closed");
        this.session = null;
        triggerReconnectAsync("websocket close");
    }
}
