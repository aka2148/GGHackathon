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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
class EscrowLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(EscrowLifecycleListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EscrowService escrowService;
    private final EscrowIntentStore escrowIntentStore;
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

    EscrowLifecycleListener(EscrowService escrowService,
                            EscrowIntentStore escrowIntentStore) {
        this.escrowService = escrowService;
        this.escrowIntentStore = escrowIntentStore;
    }

    EscrowIntentStore.EscrowIntent upsertIntent(String stationId,
                                                BigInteger holdAmountWei,
                                                Integer targetSoc,
                                                Long timeoutSeconds,
                                                String chargerWallet) {
        return escrowIntentStore.upsert(stationId, holdAmountWei, targetSoc, timeoutSeconds, chargerWallet);
    }

    Optional<EscrowIntentStore.EscrowIntent> latestIntent(String stationId) {
        return escrowIntentStore.get(stationId);
    }

    EscrowBindingStatus activeBindingStatus(String stationId) {
        String resolvedStationId = normalizeStationId(stationId);
        CompletableFuture<EscrowSessionBinding> future = stationEscrows.get(resolvedStationId);
        if (future == null) {
            return EscrowBindingStatus.notCreated(resolvedStationId);
        }

        if (!future.isDone()) {
            return EscrowBindingStatus.creating(resolvedStationId);
        }

        try {
            EscrowSessionBinding binding = future.join();
            return binding.toStatus("Active escrow binding available.");
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return EscrowBindingStatus.failed(resolvedStationId, message);
        }
    }

    EscrowBindingStatus resetBinding(String stationId, boolean clearIntent) {
        String resolvedStationId = normalizeStationId(stationId);
        stationEscrows.remove(resolvedStationId);
        if (clearIntent) {
            escrowIntentStore.clear(resolvedStationId);
        }
        return EscrowBindingStatus.notCreated(resolvedStationId);
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
            .thenCompose(binding -> escrowService.authorizeSession(binding.escrowAddress(), reportedHash)
                .thenApply(txHash -> {
                    binding.markTransition("AUTHORIZED", txHash);
                    return txHash;
                }))
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
            .thenCompose(binding -> escrowService.refundSession(binding.escrowAddress(), "FIRMWARE_TAMPERED")
                .thenApply(txHash -> {
                    binding.markTransition("REFUNDED", txHash);
                    return txHash;
                }))
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

        String normalizedState = switch (state.trim().toUpperCase(Locale.ROOT)) {
            case "STARTED" -> "START";
            case "UPDATED" -> "UPDATE";
            case "ENDED" -> "END";
            case "CANCELLED" -> "CANCEL";
            default -> state.trim().toUpperCase(Locale.ROOT);
        };
        return switch (normalizedState) {
            case "START" -> {
                binding.setSessionId(sessionId);
                yield escrowService.startCharging(binding.escrowAddress(), sessionId)
                    .thenApply(txHash -> {
                        binding.markTransition("CHARGING", txHash);
                        return txHash;
                    });
            }
            case "UPDATE" -> {
                if (soc == null) {
                    yield CompletableFuture.completedFuture(null);
                }
                yield escrowService.updateSoc(binding.escrowAddress(), soc)
                    .thenApply(txHash -> {
                        binding.markTransition("CHARGING", txHash);
                        return txHash;
                    });
            }
            case "END", "STOP", "COMPLETED", "FINISHING" -> escrowService
                .completeSession(binding.escrowAddress())
                .thenCompose(completeTx -> {
                    binding.markTransition("COMPLETED", completeTx);
                    return escrowService.releaseFunds(binding.escrowAddress())
                        .thenApply(releaseTx -> {
                            binding.markTransition("RELEASED", releaseTx);
                            return releaseTx;
                        });
                });
            case "CANCEL", "FAULTED", "ERROR" -> escrowService
                .refundSession(binding.escrowAddress(), "SESSION_ABORTED_" + normalizedState)
                .thenApply(txHash -> {
                    binding.markTransition("REFUNDED", txHash);
                    return txHash;
                });
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
        EscrowCreateSettings settings = consumeCreateSettings(stationId);

        CompletableFuture<EscrowSessionBinding> created = escrowService
            .deployEscrow(
                stationId,
                settings.chargerWallet(),
                goldenHash,
                settings.targetSoc(),
                settings.timeoutSeconds()
            )
            .thenCompose(address -> {
                EscrowSessionBinding binding = new EscrowSessionBinding(
                    stationId,
                    address,
                    goldenHash,
                    settings.chargerWallet(),
                    Instant.now()
                );
                binding.markTransition("CREATED", null);

                if (!autoDepositEnabled) {
                    binding.setHeldAmountWei(settings.holdAmountWei());
                    return CompletableFuture.completedFuture(binding);
                }

                return escrowService.deposit(address, settings.holdAmountWei())
                    .thenApply(txHash -> {
                        binding.markDeposit(settings.holdAmountWei(), txHash);
                        binding.markTransition("FUNDED", txHash);
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

    private String normalizeStationId(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return "CS-101";
        }
        return stationId;
    }

    private EscrowCreateSettings consumeCreateSettings(String stationId) {
        EscrowIntentStore.EscrowIntent intent = escrowIntentStore.clear(stationId).orElse(null);

        String chargerWallet = intent == null || intent.chargerWallet() == null || intent.chargerWallet().isBlank()
            ? resolveChargerWallet()
            : intent.chargerWallet();
        int targetSoc = normalizeTargetSoc(intent == null ? null : intent.targetSoc());
        long timeoutSeconds = normalizeTimeoutSeconds(intent == null ? null : intent.timeoutSeconds());
        BigInteger holdAmountWei = normalizeHoldAmount(intent == null ? null : intent.holdAmountWei());

        if (intent == null) {
            log.info("[Escrow] Creating escrow with defaults stationId={} targetSoc={} timeoutSeconds={} holdAmountWei={}",
                stationId, targetSoc, timeoutSeconds, holdAmountWei);
        } else {
            log.info("[Escrow] Creating escrow from consumed intent stationId={} targetSoc={} timeoutSeconds={} holdAmountWei={} intentCreatedAt={}",
                stationId, targetSoc, timeoutSeconds, holdAmountWei, intent.createdAt());
        }

        return new EscrowCreateSettings(chargerWallet, targetSoc, timeoutSeconds, holdAmountWei);
    }

    private int normalizeTargetSoc(Integer targetSoc) {
        int resolved = targetSoc == null ? defaultTargetSoc : targetSoc;
        return Math.max(1, Math.min(100, resolved));
    }

    private long normalizeTimeoutSeconds(Long timeoutSeconds) {
        long resolved = timeoutSeconds == null ? defaultTimeoutSeconds : timeoutSeconds;
        return resolved > 0 ? resolved : defaultTimeoutSeconds;
    }

    private BigInteger normalizeHoldAmount(BigInteger holdAmountWei) {
        if (holdAmountWei == null || holdAmountWei.signum() <= 0) {
            return defaultDepositWei;
        }
        return holdAmountWei;
    }

    private String extractSessionId(String rawPayload) {
        try {
            JsonNode payload = MAPPER.readTree(rawPayload);
            String id = textValue(payload, "sessionId", "transactionId", "transactionData", "txId");
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
        private volatile String deployTxHash;
        private volatile String depositTxHash;
        private volatile String lifecycleState = "CREATED";
        private volatile BigInteger heldAmountWei = BigInteger.ZERO;
        private volatile String lastTxHash;
        private volatile Instant lastUpdatedAt = Instant.now();

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
            this.lastUpdatedAt = Instant.now();
        }

        private void setHeldAmountWei(BigInteger heldAmountWei) {
            this.heldAmountWei = heldAmountWei == null ? BigInteger.ZERO : heldAmountWei;
            this.lastUpdatedAt = Instant.now();
        }

        private void markDeposit(BigInteger heldAmountWei, String txHash) {
            this.heldAmountWei = heldAmountWei == null ? BigInteger.ZERO : heldAmountWei;
            this.depositTxHash = txHash;
            this.lastTxHash = txHash;
            this.lastUpdatedAt = Instant.now();
        }

        private void markTransition(String lifecycleState, String txHash) {
            this.lifecycleState = lifecycleState;
            if (txHash != null && !txHash.isBlank()) {
                this.lastTxHash = txHash;
            }
            this.lastUpdatedAt = Instant.now();
        }

        private EscrowBindingStatus toStatus(String message) {
            return new EscrowBindingStatus(
                stationId,
                escrowAddress,
                deployTxHash,
                depositTxHash,
                lifecycleState,
                heldAmountWei,
                sessionId,
                goldenHash,
                chargerWallet,
                createdAt,
                lastUpdatedAt,
                message
            );
        }
    }

    private record EscrowCreateSettings(
        String chargerWallet,
        int targetSoc,
        long timeoutSeconds,
        BigInteger holdAmountWei
    ) {
    }
}

record EscrowBindingStatus(
    String stationId,
    String escrowAddress,
    String deployTxHash,
    String depositTxHash,
    String lifecycleState,
    BigInteger heldAmountWei,
    String sessionId,
    String goldenHash,
    String chargerWallet,
    Instant createdAt,
    Instant lastUpdated,
    String message
) {
    static EscrowBindingStatus notCreated(String stationId) {
        return new EscrowBindingStatus(
            stationId,
            null,
            null,
            null,
            "NOT_CREATED",
            BigInteger.ZERO,
            null,
            null,
            null,
            null,
            Instant.now(),
            "No escrow binding exists yet for this station."
        );
    }

    static EscrowBindingStatus creating(String stationId) {
        return new EscrowBindingStatus(
            stationId,
            null,
            null,
            null,
            "CREATING",
            BigInteger.ZERO,
            null,
            null,
            null,
            null,
            Instant.now(),
            "Escrow binding is being created."
        );
    }

    static EscrowBindingStatus failed(String stationId, String reason) {
        return new EscrowBindingStatus(
            stationId,
            null,
            null,
            null,
            "FAILED",
            BigInteger.ZERO,
            null,
            null,
            null,
            null,
            Instant.now(),
            reason == null || reason.isBlank() ? "Escrow status lookup failed." : reason
        );
    }
}
