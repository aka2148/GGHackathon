package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.orchestrator.websocket.FirmwareStatusEvent;
import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FirmwareStatusVerificationListenerTest {

    @Mock
    private BlockchainService blockchainService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private LatestTrustVerdictStore latestTrustVerdictStore;

    @Captor
    private ArgumentCaptor<Object> eventCaptor;

    @InjectMocks
    private FirmwareStatusVerificationListener listener;

    @Test
    void publishesVerifiedEventWhenHashesMatch() {
        FirmwareHash verified = FirmwareHash.builder()
            .stationId("CS-101")
            .reportedHash("0xabc123")
            .goldenHash("0xabc123")
            .firmwareVersion("1.0.0")
            .reportedAt(Instant.now())
            .status(FirmwareHash.VerificationStatus.VERIFIED)
            .build();

        TrustEvidence evidence = new TrustEvidence(
            "CS-101",
            TrustEvidence.Verdict.VERIFIED,
            "0xabc123",
            "0xabc123",
            "ACME-MFG",
            "sig",
            true,
            "0xcontract",
            null,
            TrustEvidence.RpcStatus.REACHABLE,
            Instant.now(),
            "Reported hash matches on-chain golden hash.",
            40L,
            TrustEvidence.LatencyBand.FAST
        );

        doReturn(CompletableFuture.completedFuture(new TrustVerificationResult(verified, evidence)))
            .when(blockchainService)
            .verifyGoldenHashWithEvidence(any(FirmwareHash.class));

        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-101",
            "{\"reportedHash\":\"0xabc123\",\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(GoldenHashVerifiedEvent.class::isInstance);
    }

    @Test
    void publishesTamperedEventWhenHashesDiffer() {
        FirmwareHash tampered = FirmwareHash.builder()
            .stationId("CS-102")
            .reportedHash("0xdeadbeef")
            .goldenHash("0xabc123")
            .firmwareVersion("1.0.0")
            .reportedAt(Instant.now())
            .status(FirmwareHash.VerificationStatus.TAMPERED)
            .build();

        TrustEvidence evidence = new TrustEvidence(
            "CS-102",
            TrustEvidence.Verdict.TAMPERED,
            "0xdeadbeef",
            "0xabc123",
            "ACME-MFG",
            "sig",
            true,
            "0xcontract",
            null,
            TrustEvidence.RpcStatus.REACHABLE,
            Instant.now(),
            "Reported hash differs from on-chain golden hash.",
            60L,
            TrustEvidence.LatencyBand.FAST
        );

        doReturn(CompletableFuture.completedFuture(new TrustVerificationResult(tampered, evidence)))
            .when(blockchainService)
            .verifyGoldenHashWithEvidence(any(FirmwareHash.class));

        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-102",
            "{\"reportedHash\":\"0xdeadbeef\",\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(GoldenHashTamperedEvent.class::isInstance);
    }

    @Test
    void publishesFailureEventWhenPayloadCannotBeParsed() {
        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-103",
            "{\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(blockchainService, never()).verifyGoldenHashWithEvidence(any(FirmwareHash.class));
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .anyMatch(GoldenHashVerificationFailedEvent.class::isInstance);
    }
}