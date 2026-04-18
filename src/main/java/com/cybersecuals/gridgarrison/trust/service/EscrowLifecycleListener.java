package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
class EscrowLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(EscrowLifecycleListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EscrowService escrowService;
    private final ConcurrentMap<String, CompletableFuture<EscrowSessionBinding>> stationEscrows =
        new ConcurrentHashMap<>();

    @Value("${gridgarrison.escrow.default-target-soc:80}")
    private int defaultTargetSoc;

    @Value("${gridgarrison.escrow.default-timeout-seconds:3600}")
    private long defaultTimeoutSeconds;

    @Value("${gridgarrison.escrow.default-deposit-wei:1000000000000000}")
    private BigInteger defaultDepositWei;

    @Value("${gridgarrison.escrow.auto-deposit-enabled:true}")
    private boolean autoDepositEnabled;

    @Value("${gridgarrison.escrow.default-charger-wallet:}")
    private String defaultChargerWallet;

    @Value("${gridgarrison.blockchain.private-key}")
    private String operatorPrivateKey;

    EscrowLifecycleListener(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @EventListener
    void onGoldenHashVerified(GoldenHashVerifiedEvent event) {
        if (event == null || event.stationId() == null || event.stationId().isBlank()) {
            return;
        }

        String reportedHash = event.reportedHash();
        String goldenHash = event.goldenHash();
        if (reportedHash == null || reportedHash.isBlank() || goldenHash == null || goldenHash.isBlank()) {
            return;
        }

        ensureEscrow(event.stationId(), goldenHash)
            .thenCompose(binding -> escrowService.authorizeSession(binding.escrowAddress(), reportedHash))
            .whenComplete((txHash, error) -> {
                if (error != null) {
                    log.warn("[Escrow] authorizeSession failed stationId={}", event.stationId(), error);
                    return;
                }
                log.info("[Escrow] station authorized stationId={} txHash={}", event.stationId(), txHash);
            });
    }

    @EventListener
    void onGoldenHashTampered(GoldenHashTamperedEvent event) {
        if (event == null || event.stationId() == null || event.stationId().isBlank()) {
            return;
        }

        String goldenHash = event.goldenHash();
        if (goldenHash == null || goldenHash.isBlank()) {
            return;
        }

        ensureEscrow(event.stationId(), goldenHash)
            .thenCompose(binding -> escrowService.refundSession(binding.escrowAddress(), "FIRMWARE_TAMPERED"))
            .whenComplete((txHash, error) -> {
                if (error != null) {
                    log.warn("[Escrow] refundSession failed stationId={}", event.stationId(), error);
                    return;
                }
                log.info("[Escrow] tampered refund issued stationId={} txHash={}", event.stationId(), txHash);
            });
    }

    @EventListener
    void onTransactionEvent(TransactionEvent event) {
        if (event == null || event.stationId() == null || event.stationId().isBlank()) {
            return;
        }

        CompletableFuture<EscrowSessionBinding> bindingFuture = stationEscrows.get(event.stationId());
        if (bindingFuture == null) {
            return;
        }

        String sessionId = extractSessionId(event.rawPayload());
        String state = extractState(event.rawPayload());
        Integer soc = extractSoc(event.rawPayload());

        bindingFuture
            .thenCompose(binding -> dispatchTransactionState(binding, state, sessionId, soc))
            .whenComplete((txHash, error) -> {
                if (error != null) {
                    log.warn("[Escrow] transaction transition failed stationId={} state={}",
                        event.stationId(), state, error);
                    return;
                }
                if (txHash != null && !txHash.isBlank()) {
                    log.info("[Escrow] transaction transition stationId={} state={} txHash={}",
                        event.stationId(), state, txHash);
                }
            });
    }

    private CompletableFuture<String> dispatchTransactionState(EscrowSessionBinding binding,
                                                               String state,
                                                               String sessionId,
                                                               Integer soc) {
        if (binding == null || state == null || state.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String normalizedState = state.toUpperCase(Locale.ROOT);
        return switch (normalizedState) {
            case "START" -> {
                binding.setSessionId(sessionId);
                yield escrowService.startCharging(binding.escrowAddress(), sessionId);
            }
            case "UPDATE" -> {
                if (soc == null) {
                    yield CompletableFuture.completedFuture(null);
                }
                yield escrowService.updateSoc(binding.escrowAddress(), soc);
            }
            case "END", "STOP", "COMPLETED", "FINISHING" -> escrowService
                .completeSession(binding.escrowAddress())
                .thenCompose(txHash -> escrowService.releaseFunds(binding.escrowAddress()));
            case "CANCEL", "FAULTED", "ERROR" -> escrowService
                .refundSession(binding.escrowAddress(), "SESSION_ABORTED_" + normalizedState);
            default -> CompletableFuture.completedFuture(null);
        };
    }

    private CompletableFuture<EscrowSessionBinding> ensureEscrow(String stationId, String goldenHash) {
        CompletableFuture<EscrowSessionBinding> future = stationEscrows.computeIfAbsent(
            stationId,
            id -> createEscrow(id, goldenHash)
        );

        return future.thenApply(binding -> {
            binding.setGoldenHash(goldenHash);
            return binding;
        });
    }

    private CompletableFuture<EscrowSessionBinding> createEscrow(String stationId, String goldenHash) {
        String chargerWallet = resolveChargerWallet();

        CompletableFuture<EscrowSessionBinding> created = escrowService
            .deployEscrow(
                stationId,
                chargerWallet,
                goldenHash,
                defaultTargetSoc,
                defaultTimeoutSeconds
            )
            .thenCompose(address -> {
                EscrowSessionBinding binding = new EscrowSessionBinding(
                    stationId,
                    address,
                    goldenHash,
                    chargerWallet,
                    Instant.now()
                );

                if (!autoDepositEnabled) {
                    return CompletableFuture.completedFuture(binding);
                }

                return escrowService.deposit(address, defaultDepositWei)
                    .thenApply(txHash -> {
                        binding.setLastDepositTxHash(txHash);
                        return binding;
                    });
            });

        created.whenComplete((ignored, error) -> {
            if (error != null) {
                stationEscrows.remove(stationId, created);
            }
        });

        return created;
    }

    private String resolveChargerWallet() {
        if (defaultChargerWallet != null && !defaultChargerWallet.isBlank()) {
            return defaultChargerWallet;
        }
        return Credentials.create(operatorPrivateKey).getAddress();
    }

    private String extractSessionId(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            String id = textValue(payload, "sessionId", "transactionId", "txId");
            if (id != null && !id.isBlank()) {
                return id;
            }
        } catch (Exception ignored) {
        }
        return "SESSION-UNKNOWN";
    }

    private String extractState(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            String state = textValue(payload, "state", "eventType", "type");
            if (state != null && !state.isBlank()) {
                return state;
            }
        } catch (Exception ignored) {
        }
        return "UPDATE";
    }

    private Integer extractSoc(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            String value = textValue(payload, "soc", "currentSoc", "batterySoc");
            if (value == null || value.isBlank()) {
                return null;
            }
            int parsed = Integer.parseInt(value);
            return Math.max(0, Math.min(parsed, 100));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String textValue(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static final class EscrowSessionBinding {
        private final String stationId;
        private final String escrowAddress;
        private final String chargerWallet;
        private final Instant createdAt;

        private volatile String goldenHash;
        private volatile String sessionId;
        private volatile String lastDepositTxHash;

        private EscrowSessionBinding(String stationId,
                                     String escrowAddress,
                                     String goldenHash,
                                     String chargerWallet,
                                     Instant createdAt) {
            this.stationId = stationId;
            this.escrowAddress = escrowAddress;
            this.goldenHash = goldenHash;
            this.chargerWallet = chargerWallet;
            this.createdAt = createdAt;
        }

        private String escrowAddress() {
            return escrowAddress;
        }

        private void setGoldenHash(String goldenHash) {
            this.goldenHash = goldenHash;
        }

        private void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        private void setLastDepositTxHash(String lastDepositTxHash) {
            this.lastDepositTxHash = lastDepositTxHash;
        }
    }
}
