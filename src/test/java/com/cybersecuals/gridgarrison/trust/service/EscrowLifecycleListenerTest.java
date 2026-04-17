package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.orchestrator.websocket.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscrowLifecycleListenerTest {

    @Mock
    private EscrowService escrowService;

    private EscrowLifecycleListener listener;

    @BeforeEach
    void setUp() {
        listener = new EscrowLifecycleListener(escrowService);
        ReflectionTestUtils.setField(listener, "defaultTargetSoc", 80);
        ReflectionTestUtils.setField(listener, "defaultTimeoutSeconds", 3600L);
        ReflectionTestUtils.setField(listener, "defaultDepositWei", BigInteger.valueOf(1_000_000L));
        ReflectionTestUtils.setField(listener, "autoDepositEnabled", true);
        ReflectionTestUtils.setField(listener, "defaultChargerWallet", "0x1000000000000000000000000000000000000001");
        ReflectionTestUtils.setField(listener, "operatorPrivateKey",
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082797f0d4f2f6f5f3d2c6f");
    }

    @Test
    void verifiedEventDeploysFundsAndAuthorizesEscrow() {
        when(escrowService.deployEscrow(anyString(), anyString(), anyString(), anyInt(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture("0xescrow"));
        when(escrowService.deposit(eq("0xescrow"), eq(BigInteger.valueOf(1_000_000L))))
            .thenReturn(CompletableFuture.completedFuture("0xdeposit"));
        when(escrowService.authorizeSession(eq("0xescrow"), eq("0xlive")))
            .thenReturn(CompletableFuture.completedFuture("0xauth"));

        listener.onGoldenHashVerified(new GoldenHashVerifiedEvent(
            "CS-101",
            "{}",
            "0xlive",
            "0xgold",
            null
        ));

        verify(escrowService, timeout(500)).deployEscrow(
            eq("CS-101"),
            eq("0x1000000000000000000000000000000000000001"),
            eq("0xgold"),
            eq(80),
            eq(3600L)
        );
        verify(escrowService, timeout(500)).deposit(eq("0xescrow"), eq(BigInteger.valueOf(1_000_000L)));
        verify(escrowService, timeout(500)).authorizeSession(eq("0xescrow"), eq("0xlive"));
    }

    @Test
    void tamperedEventDeploysAndRefundsEscrow() {
        when(escrowService.deployEscrow(anyString(), anyString(), anyString(), anyInt(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture("0xescrow"));
        when(escrowService.deposit(eq("0xescrow"), eq(BigInteger.valueOf(1_000_000L))))
            .thenReturn(CompletableFuture.completedFuture("0xdeposit"));
        when(escrowService.refundSession(eq("0xescrow"), eq("FIRMWARE_TAMPERED")))
            .thenReturn(CompletableFuture.completedFuture("0xrefund"));

        listener.onGoldenHashTampered(new GoldenHashTamperedEvent(
            "CS-TAMPER-01",
            "{}",
            "0xdead",
            "0xgold",
            null
        ));

        verify(escrowService, timeout(500)).deployEscrow(
            eq("CS-TAMPER-01"),
            eq("0x1000000000000000000000000000000000000001"),
            eq("0xgold"),
            eq(80),
            eq(3600L)
        );
        verify(escrowService, timeout(500)).refundSession(eq("0xescrow"), eq("FIRMWARE_TAMPERED"));
    }

    @Test
    void transactionStartUsesDeployedEscrowBinding() {
        when(escrowService.deployEscrow(anyString(), anyString(), anyString(), anyInt(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture("0xescrow"));
        when(escrowService.deposit(eq("0xescrow"), eq(BigInteger.valueOf(1_000_000L))))
            .thenReturn(CompletableFuture.completedFuture("0xdeposit"));
        when(escrowService.authorizeSession(eq("0xescrow"), eq("0xlive")))
            .thenReturn(CompletableFuture.completedFuture("0xauth"));
        when(escrowService.startCharging(eq("0xescrow"), eq("SESSION-42")))
            .thenReturn(CompletableFuture.completedFuture("0xstart"));

        listener.onGoldenHashVerified(new GoldenHashVerifiedEvent(
            "CS-101",
            "{}",
            "0xlive",
            "0xgold",
            null
        ));

        listener.onTransactionEvent(new TransactionEvent(
            "CS-101",
            "{\"sessionId\":\"SESSION-42\",\"eventType\":\"START\"}"
        ));

        verify(escrowService, timeout(500)).startCharging(eq("0xescrow"), eq("SESSION-42"));
    }
}
