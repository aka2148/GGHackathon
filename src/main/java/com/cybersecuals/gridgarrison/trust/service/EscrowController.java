package com.cybersecuals.gridgarrison.trust.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.crypto.Credentials;

import java.math.BigInteger;
import java.time.Instant;

@RestController
@RequestMapping("/trust/api/escrow")
class EscrowController {

    private final EscrowService escrowService;
    private final EscrowLifecycleListener escrowLifecycleListener;

    @Value("${gridgarrison.escrow.default-target-soc:80}")
    private int defaultTargetSoc;

    @Value("${gridgarrison.escrow.default-timeout-seconds:3600}")
    private long defaultTimeoutSeconds;

    @Value("${gridgarrison.escrow.default-deposit-wei:1000000000000000}")
    private BigInteger defaultDepositWei;

    @Value("${gridgarrison.escrow.default-charger-wallet:}")
    private String defaultChargerWallet;

    @Value("${gridgarrison.blockchain.private-key}")
    private String operatorPrivateKey;

    EscrowController(EscrowService escrowService,
                     EscrowLifecycleListener escrowLifecycleListener) {
        this.escrowService = escrowService;
        this.escrowLifecycleListener = escrowLifecycleListener;
    }

    @PostMapping("/intent")
    EscrowIntentResponse registerIntent(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam(required = false) BigInteger holdAmountWei,
        @RequestParam(required = false) Integer targetSoc,
        @RequestParam(required = false) Long timeoutSeconds,
        @RequestParam(required = false) String chargerWallet
    ) {
        BigInteger effectiveHoldAmount = (holdAmountWei == null || holdAmountWei.signum() <= 0)
            ? defaultDepositWei
            : holdAmountWei;
        Integer effectiveTargetSoc = targetSoc == null
            ? defaultTargetSoc
            : Math.max(1, Math.min(100, targetSoc));
        Long effectiveTimeoutSeconds = (timeoutSeconds == null || timeoutSeconds <= 0)
            ? defaultTimeoutSeconds
            : timeoutSeconds;
        String effectiveCharger = (chargerWallet == null || chargerWallet.isBlank())
            ? resolveChargerWallet()
            : chargerWallet;

        EscrowIntentStore.EscrowIntent intent = escrowLifecycleListener.upsertIntent(
            stationId,
            effectiveHoldAmount,
            effectiveTargetSoc,
            effectiveTimeoutSeconds,
            effectiveCharger
        );
        return EscrowIntentResponse.fromIntent("OK", intent, "Escrow intent registered.");
    }

    @GetMapping("/intent")
    EscrowIntentResponse intent(@RequestParam(defaultValue = "CS-101") String stationId) {
        return escrowLifecycleListener
            .latestIntent(stationId)
            .map(intent -> EscrowIntentResponse.fromIntent("OK", intent, "Escrow intent available."))
            .orElseGet(() -> EscrowIntentResponse.missing(stationId));
    }

    @GetMapping("/active")
    EscrowBindingStatus active(@RequestParam(defaultValue = "CS-101") String stationId) {
        return escrowLifecycleListener.activeBindingStatus(stationId);
    }

    @PostMapping("/reset")
    EscrowBindingStatus reset(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam(defaultValue = "true") boolean clearIntent
    ) {
        return escrowLifecycleListener.resetBinding(stationId, clearIntent);
    }

    @PostMapping("/deploy")
    EscrowActionResponse deploy(
        @RequestParam(defaultValue = "CS-101") String stationId,
        @RequestParam String goldenHash,
        @RequestParam(required = false) String chargerWallet,
        @RequestParam(required = false) Integer targetSoc,
        @RequestParam(required = false) Long timeoutSeconds,
        @RequestParam(required = false) BigInteger depositWei,
        @RequestParam(defaultValue = "true") boolean autoDeposit
    ) {
        try {
            String effectiveCharger = (chargerWallet == null || chargerWallet.isBlank())
                ? resolveChargerWallet()
                : chargerWallet;
            int effectiveTargetSoc = targetSoc == null ? defaultTargetSoc : targetSoc;
            long effectiveTimeoutSeconds = timeoutSeconds == null ? defaultTimeoutSeconds : timeoutSeconds;

            String escrowAddress = escrowService
                .deployEscrow(stationId, effectiveCharger, goldenHash, effectiveTargetSoc, effectiveTimeoutSeconds)
                .join();

            String depositTxHash = null;
            if (autoDeposit) {
                BigInteger effectiveDeposit = depositWei == null ? defaultDepositWei : depositWei;
                depositTxHash = escrowService.deposit(escrowAddress, effectiveDeposit).join();
            }

            return EscrowActionResponse.ok(
                stationId,
                escrowAddress,
                depositTxHash,
                "Escrow deployed" + (autoDeposit ? " and funded" : "")
            );
        } catch (Exception ex) {
            return EscrowActionResponse.failed(stationId, null, ex);
        }
    }

    @PostMapping("/deposit")
    EscrowActionResponse deposit(
        @RequestParam String escrowAddress,
        @RequestParam BigInteger amountWei
    ) {
        try {
            String txHash = escrowService.deposit(escrowAddress, amountWei).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Escrow funded");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/authorize")
    EscrowActionResponse authorize(
        @RequestParam String escrowAddress,
        @RequestParam String liveHash
    ) {
        try {
            String txHash = escrowService.authorizeSession(escrowAddress, liveHash).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Escrow authorized");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/start")
    EscrowActionResponse start(
        @RequestParam String escrowAddress,
        @RequestParam String sessionId
    ) {
        try {
            String txHash = escrowService.startCharging(escrowAddress, sessionId).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Charging started");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/soc")
    EscrowActionResponse updateSoc(
        @RequestParam String escrowAddress,
        @RequestParam int soc
    ) {
        try {
            String txHash = escrowService.updateSoc(escrowAddress, soc).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "State of charge updated");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/complete")
    EscrowActionResponse complete(@RequestParam String escrowAddress) {
        try {
            String txHash = escrowService.completeSession(escrowAddress).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Session completed");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/release")
    EscrowActionResponse release(@RequestParam String escrowAddress) {
        try {
            String txHash = escrowService.releaseFunds(escrowAddress).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Funds released");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @PostMapping("/refund")
    EscrowActionResponse refund(
        @RequestParam String escrowAddress,
        @RequestParam(defaultValue = "MANUAL_REFUND") String reason
    ) {
        try {
            String txHash = escrowService.refundSession(escrowAddress, reason).join();
            return EscrowActionResponse.ok(null, escrowAddress, txHash, "Refund issued");
        } catch (Exception ex) {
            return EscrowActionResponse.failed(null, escrowAddress, ex);
        }
    }

    @GetMapping("/state")
    EscrowStateResponse state(@RequestParam String escrowAddress) {
        try {
            BigInteger state = escrowService.getSessionState(escrowAddress).join();
            return EscrowStateResponse.ok(escrowAddress, state, "OK");
        } catch (Exception ex) {
            return EscrowStateResponse.failed(escrowAddress, ex);
        }
    }

    private String resolveChargerWallet() {
        if (defaultChargerWallet != null && !defaultChargerWallet.isBlank()) {
            return defaultChargerWallet;
        }
        return Credentials.create(operatorPrivateKey).getAddress();
    }

    record EscrowActionResponse(
        String status,
        String stationId,
        String escrowAddress,
        String txHash,
        String message,
        Instant observedAt
    ) {
        static EscrowActionResponse ok(String stationId,
                                       String escrowAddress,
                                       String txHash,
                                       String message) {
            return new EscrowActionResponse("OK", stationId, escrowAddress, txHash, message, Instant.now());
        }

        static EscrowActionResponse failed(String stationId,
                                           String escrowAddress,
                                           Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return new EscrowActionResponse("FAILED", stationId, escrowAddress, null, message, Instant.now());
        }
    }

    record EscrowStateResponse(
        String status,
        String escrowAddress,
        BigInteger state,
        String message,
        Instant observedAt
    ) {
        static EscrowStateResponse ok(String escrowAddress, BigInteger state, String message) {
            return new EscrowStateResponse("OK", escrowAddress, state, message, Instant.now());
        }

        static EscrowStateResponse failed(String escrowAddress, Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return new EscrowStateResponse("FAILED", escrowAddress, null, message, Instant.now());
        }
    }

    record EscrowIntentResponse(
        String status,
        String stationId,
        BigInteger holdAmountWei,
        Integer targetSoc,
        Long timeoutSeconds,
        String chargerWallet,
        Instant createdAt,
        String message
    ) {
        static EscrowIntentResponse fromIntent(String status,
                                               EscrowIntentStore.EscrowIntent intent,
                                               String message) {
            return new EscrowIntentResponse(
                status,
                intent.stationId(),
                intent.holdAmountWei(),
                intent.targetSoc(),
                intent.timeoutSeconds(),
                intent.chargerWallet(),
                intent.createdAt(),
                message
            );
        }

        static EscrowIntentResponse missing(String stationId) {
            return new EscrowIntentResponse(
                "NOT_FOUND",
                stationId,
                null,
                null,
                null,
                null,
                Instant.now(),
                "No escrow intent available for station."
            );
        }
    }
}
