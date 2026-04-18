package com.cybersecuals.gridgarrison.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EvUserJourneyState {

    public enum JourneyState {
        DISCONNECTED,
        HANDSHAKING,
        VERIFIED,
        CONTRACT_CREATING,
        CONTRACT_CREATED,
        CHARGING,
        SETTLING,
        COMPLETE
    }

    public enum InputMode {
        MONEY,
        VOLTS
    }

    public record ChargeIntent(
        InputMode inputMode,
        double inputValue,
        double estimatedKwh,
        BigInteger holdAmountWei,
        int targetSoc,
        Instant createdAt
    ) {
    }

    public record Snapshot(
        JourneyState journeyState,
        ChargeIntent chargeIntent,
        String trustStatus,
        String escrowStatus,
        String escrowAddress,
        String escrowAddressShort,
        BigInteger heldAmountWei,
        int batteryPct,
        double walletBalance,
        Instant updatedAt
    ) {
    }

    private final AtomicReference<JourneyState> journeyState =
        new AtomicReference<>(JourneyState.DISCONNECTED);
    private final AtomicReference<ChargeIntent> chargeIntent = new AtomicReference<>();
    private final AtomicReference<String> trustStatus = new AtomicReference<>("UNVERIFIED");
    private final AtomicReference<String> escrowStatus = new AtomicReference<>("NOT_CREATED");
    private final AtomicReference<String> escrowAddress = new AtomicReference<>();
    private final AtomicReference<BigInteger> heldAmountWei = new AtomicReference<>(BigInteger.ZERO);
    private final AtomicReference<Integer> batteryPct;
    private final AtomicReference<Double> walletBalance;
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.now());
    private final double initialWalletBalance;
    private final int initialBatteryPct;

    public EvUserJourneyState(
        @Value("${ev.simulator.user.initial-wallet-balance:100.0}") double initialWalletBalance,
        @Value("${ev.simulator.user.initial-battery-pct:20}") int initialBatteryPct
    ) {
        this.initialWalletBalance = Math.max(0.0d, initialWalletBalance);
        this.initialBatteryPct = clampBattery(initialBatteryPct);
        this.walletBalance = new AtomicReference<>(this.initialWalletBalance);
        this.batteryPct = new AtomicReference<>(this.initialBatteryPct);
    }

    public Snapshot snapshot() {
        String address = escrowAddress.get();
        return new Snapshot(
            journeyState.get(),
            chargeIntent.get(),
            trustStatus.get(),
            escrowStatus.get(),
            address,
            shortAddress(address),
            heldAmountWei.get(),
            batteryPct.get(),
            walletBalance.get(),
            updatedAt.get()
        );
    }

    public void markDisconnected() {
        journeyState.set(JourneyState.DISCONNECTED);
        touch();
    }

    public void markHandshakeStarted() {
        journeyState.set(JourneyState.HANDSHAKING);
        touch();
    }

    public void markVerified(String status) {
        trustStatus.set(defaultValue(status, "VERIFIED"));
        journeyState.set(JourneyState.VERIFIED);
        touch();
    }

    public void markContractCreating() {
        escrowStatus.set("CREATING");
        journeyState.set(JourneyState.CONTRACT_CREATING);
        touch();
    }

    public void markContractCreated(String status,
                                    String address,
                                    BigInteger holdAmount) {
        escrowStatus.set(defaultValue(status, "CREATED"));
        escrowAddress.set(address);
        heldAmountWei.set(holdAmount == null ? BigInteger.ZERO : holdAmount);
        journeyState.set(JourneyState.CONTRACT_CREATED);
        touch();
    }

    public void markCharging() {
        escrowStatus.set(defaultValue(escrowStatus.get(), "CREATED"));
        journeyState.set(JourneyState.CHARGING);
        touch();
    }

    public void markSettling() {
        journeyState.set(JourneyState.SETTLING);
        touch();
    }

    public void markComplete() {
        journeyState.set(JourneyState.COMPLETE);
        touch();
    }

    public void updateChargeIntent(ChargeIntent intent) {
        chargeIntent.set(intent);
        if (intent != null) {
            heldAmountWei.set(intent.holdAmountWei() == null ? BigInteger.ZERO : intent.holdAmountWei());
        }
        touch();
    }

    public void updateTrustStatus(String status) {
        trustStatus.set(defaultValue(status, "UNVERIFIED"));
        touch();
    }

    public void updateEscrowStatus(String status,
                                   String address,
                                   BigInteger holdAmount) {
        escrowStatus.set(defaultValue(status, "NOT_CREATED"));
        if (address != null && !address.isBlank()) {
            escrowAddress.set(address);
        }
        if (holdAmount != null) {
            heldAmountWei.set(holdAmount);
        }
        touch();
    }

    public void setWalletBalance(double value) {
        walletBalance.set(Math.max(0.0d, value));
        touch();
    }

    public void setBatteryPct(int value) {
        batteryPct.set(clampBattery(value));
        touch();
    }

    public void resetJourney(boolean resetWallet, boolean resetBattery) {
        journeyState.set(JourneyState.DISCONNECTED);
        chargeIntent.set(null);
        trustStatus.set("UNVERIFIED");
        escrowStatus.set("NOT_CREATED");
        escrowAddress.set(null);
        heldAmountWei.set(BigInteger.ZERO);

        if (resetWallet) {
            walletBalance.set(initialWalletBalance);
        }
        if (resetBattery) {
            batteryPct.set(initialBatteryPct);
        }

        touch();
    }

    private void touch() {
        updatedAt.set(Instant.now());
    }

    private String shortAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (address.length() <= 14) {
            return address;
        }
        return address.substring(0, 8) + "..." + address.substring(address.length() - 4);
    }

    private int clampBattery(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String defaultValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
