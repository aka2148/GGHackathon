package com.cybersecuals.gridgarrison.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final String OCPP_201 = "ocpp2.0.1";

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

    @Value("${ev.simulator.iso15118.enabled:true}")
    private boolean iso15118Enabled;

    @Value("${ev.simulator.iso15118.authorization-mode:EIM}")
    private String isoAuthorizationMode;

    @Value("${ev.simulator.iso15118.contract-certificate-id:}")
    private String isoContractCertificateId;

    @Value("${ev.simulator.iso15118.secc-id:SECC-LOCAL-001}")
    private String isoSeccId;

    private final EvTrustVerificationClient verificationClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(false);
    private final AtomicReference<String> lastFirmwareHash = new AtomicReference<>("");

    private volatile GridGarrisonWebSocketClient webSocketClient;

    public String getStationId() {
        return stationId;
    }

    public String getLastFirmwareHash() {
        return lastFirmwareHash.get();
    }

    /**
     * Initiates the WebSocket connection to GridGarrison.
     */
    public synchronized void connect() throws Exception {
        String endpoint = gatewayUri.replace("{stationId}", stationId);
        URI endpointUri = new URI(endpoint);
        manualDisconnect.set(false);

        log.info("🔗 Connecting to {} with stationId={}", endpoint, stationId);

        GridGarrisonWebSocketClient client = createWebSocketClient(endpointUri);
        webSocketClient = client;

        if (!client.connectBlocking(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out connecting to " + endpoint);
        }

        log.info("✅ Connected to GridGarrison");
    }

    private GridGarrisonWebSocketClient createWebSocketClient(URI endpointUri) throws Exception {
        Draft_6455 draft = new Draft_6455(
            Collections.emptyList(),
            Collections.singletonList(new Protocol(OCPP_201))
        );

        GridGarrisonWebSocketClient client = new GridGarrisonWebSocketClient(endpointUri, draft);
        if (endpointUri.getScheme() != null && endpointUri.getScheme().equalsIgnoreCase("wss")) {
            if (!tlsEnabled) {
                log.warn("Using wss:// endpoint with default JVM SSL context (ev.simulator.tls.enabled=false)");
                return client;
            }

            SSLContext sslContext = buildSslContext();
            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            client.setSocketFactory(socketFactory);
            log.info("Custom TLS context configured for simulator WebSocket client");
        }

        return client;
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
        if (isConnected()) {
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
        if (isConnected()) {
            return;
        }
        if (manualDisconnect.get()) {
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
        if (!isConnected()) {
            log.warn("Session not open, skipping Boot");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("chargingStation", stationId);
        payloadMap.put("reason", "PowerUp");
        payloadMap.put("firmwareVersion", "1.0.0");
        payloadMap.put("iso15118Enabled", iso15118Enabled);
        payloadMap.put("authorizationMode", isoAuthorizationMode);
        payloadMap.put("seccId", isoSeccId);
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"BootNotification\", %s]",
            UUID.randomUUID(),
            payload
        );

        log.debug("📤 Boot: {}", ocppFrame);
        webSocketClient.send(ocppFrame);
    }

    /**
     * Sends a Heartbeat every heartbeat interval.
     */
    @Scheduled(fixedDelayString = "${ev.simulator.heartbeat-interval-ms:30000}")
    public synchronized void sendHeartbeat() throws Exception {
        if (!isConnected()) {
            log.debug("Session not open, skipping Heartbeat");
            return;
        }

        String ocppFrame = String.format(
            "[2, \"%s\", \"Heartbeat\", {}]",
            UUID.randomUUID()
        );

        log.debug("💓 Heartbeat sent");
        webSocketClient.send(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (START).
     */
    public void sendTransactionStart(String transactionId) throws Exception {
        sendTransactionStart(transactionId, isoAuthorizationMode, isoContractCertificateId);
    }

    /**
     * Sends a TransactionEvent (START) with explicit ISO-15118 auth mode context.
     */
    public void sendTransactionStart(String transactionId, String authorizationMode, String contractCertificateId) throws Exception {
        if (!isConnected()) {
            log.warn("Session not open, skipping Transaction");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Started");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("transactionId", transactionId);
        payloadMap.put("timestamp", System.currentTimeMillis());
        payloadMap.put("meterStart", 0.0);
        payloadMap.put("iso15118Enabled", iso15118Enabled);
        payloadMap.put("authorizationMode", authorizationMode == null || authorizationMode.isBlank() ? isoAuthorizationMode : authorizationMode);
        payloadMap.put("seccId", isoSeccId);
        String resolvedContractCertificateId = contractCertificateId == null || contractCertificateId.isBlank()
            ? isoContractCertificateId
            : contractCertificateId;
        if (resolvedContractCertificateId != null && !resolvedContractCertificateId.isBlank()) {
            payloadMap.put("contractCertificateId", resolvedContractCertificateId);
        }
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID(),
            payload
        );

        log.info("🔌 Transaction START: {}", transactionId);
        webSocketClient.send(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (METER_UPDATE).
     */
    public void sendMeterUpdate(String transactionId, double kWh) throws Exception {
        if (!isConnected()) {
            log.warn("Session not open, skipping Meter");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Updated");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("transactionId", transactionId);
        payloadMap.put("meterValue", kWh);
        payloadMap.put("timestamp", System.currentTimeMillis());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID(),
            payload
        );

        log.debug("⚡ Meter update: {} kWh", kWh);
        webSocketClient.send(ocppFrame);
    }

    /**
     * Sends a TransactionEvent (END).
     */
    public void sendTransactionEnd(String transactionId, double totalKWh) throws Exception {
        if (!isConnected()) {
            log.warn("Session not open, skipping Transaction End");
            return;
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("eventType", "Ended");
        payloadMap.put("transactionData", transactionId);
        payloadMap.put("transactionId", transactionId);
        payloadMap.put("meterStop", totalKWh);
        payloadMap.put("timestamp", System.currentTimeMillis());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"TransactionEvent\", %s]",
            UUID.randomUUID(),
            payload
        );

        log.info("🛑 Transaction END: {} (total: {} kWh)", transactionId, totalKWh);
        webSocketClient.send(ocppFrame);
    }

    /**
     * Sends a FirmwareStatusNotification.
     */
    public void sendFirmwareStatus(String status, String hash) throws Exception {
        sendFirmwareStatus(status, hash, "1.0.0");
    }

    /**
     * Sends a FirmwareStatusNotification with an explicit firmware version.
     */
    public void sendFirmwareStatus(String status, String hash, String firmwareVersion) throws Exception {
        if (!isConnected()) {
            log.warn("Session not open, skipping Firmware Status");
            return;
        }

        if (hash != null && !hash.isBlank()) {
            lastFirmwareHash.set(hash);
        }

        LinkedHashMap<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("status", status);
        payloadMap.put("firmwareHash", hash);
        payloadMap.put("firmwareVersion", firmwareVersion);
        payloadMap.put("receivedAt", Instant.now().toString());
        String payload = mapper.writeValueAsString(payloadMap);

        String ocppFrame = String.format(
            "[2, \"%s\", \"FirmwareStatusNotification\", %s]",
            UUID.randomUUID(),
            payload
        );

        log.info("🔐 Firmware Status: {} | hash={}", status, hash);
        webSocketClient.send(ocppFrame);
    }

    /**
     * Closes the WebSocket session.
     */
    public synchronized void disconnect() {
        manualDisconnect.set(true);
        GridGarrisonWebSocketClient client = webSocketClient;
        if (client != null) {
            client.close();
            log.info("🔌 Disconnected from GridGarrison");
        }
        webSocketClient = null;
    }

    public boolean isConnected() {
        GridGarrisonWebSocketClient client = webSocketClient;
        return client != null && client.isOpen();
    }

    void onOpen() {
        log.info("✅ WebSocket opened");
        try {
            sendBootNotification();
            verificationClient.verifyAfterConnectAsync(
                stationId,
                null,
                null,
                this::sendFirmwareStatus
            );
        } catch (Exception e) {
            log.error("Failed to send boot notification", e);
        }
    }

    void onMessage(String message) {
        log.debug("📥 Received: {}", message);
    }

    void onError(Throwable throwable) {
        log.error("❌ WebSocket error", throwable);
        triggerReconnectAsync("websocket error");
    }

    void onClose(int code, String reason, boolean remote) {
        log.info("🔌 WebSocket closed: code={} reason={} remote={}", code, reason, remote);
        webSocketClient = null;
        if (!manualDisconnect.get()) {
            triggerReconnectAsync("websocket close");
        }
    }

    private final class GridGarrisonWebSocketClient extends WebSocketClient {
        GridGarrisonWebSocketClient(URI serverUri, Draft_6455 draft) {
            super(serverUri, draft);
        }

        @Override
        public void onOpen(ServerHandshake handshakeData) {
            EvWebSocketClient.this.onOpen();
        }

        @Override
        public void onMessage(String message) {
            EvWebSocketClient.this.onMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            EvWebSocketClient.this.onClose(code, reason, remote);
        }

        @Override
        public void onError(Exception ex) {
            EvWebSocketClient.this.onError(ex);
        }
    }
}
