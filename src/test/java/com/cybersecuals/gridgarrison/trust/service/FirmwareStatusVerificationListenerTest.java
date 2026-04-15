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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FirmwareStatusVerificationListenerTest {

    @Mock
    private BlockchainService blockchainService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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

        doReturn(CompletableFuture.completedFuture(verified))
            .when(blockchainService)
            .verifyGoldenHash(any(FirmwareHash.class));

        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-101",
            "{\"reportedHash\":\"0xabc123\",\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
            .isInstanceOf(GoldenHashVerifiedEvent.class);
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

        doReturn(CompletableFuture.completedFuture(tampered))
            .when(blockchainService)
            .verifyGoldenHash(any(FirmwareHash.class));

        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-102",
            "{\"reportedHash\":\"0xdeadbeef\",\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
            .isInstanceOf(GoldenHashTamperedEvent.class);
    }

    @Test
    void publishesFailureEventWhenPayloadCannotBeParsed() {
        listener.onFirmwareStatus(new FirmwareStatusEvent(
            "CS-103",
            "{\"firmwareVersion\":\"1.0.0\"}"
        ));

        verify(blockchainService, never()).verifyGoldenHash(any(FirmwareHash.class));
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
            .isInstanceOf(GoldenHashVerificationFailedEvent.class);
    }
}