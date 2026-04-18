package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.watchdog.service.AnomalyEvent;
import com.cybersecuals.gridgarrison.watchdog.service.DigitalTwinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowAnomalyListener {

    private final EscrowService escrowService;
    private final DigitalTwinService digitalTwinService;

    private final Map<String, String> escrowRegistry = new ConcurrentHashMap<>();

    // âœ… REGISTER ESCROW (CRITICAL FOR REFUND TO WORK)
    public void registerEscrow(String stationId, String escrowAddress) {
        escrowRegistry.put(stationId, escrowAddress);
        log.info("âœ… Escrow registered: stationId={} escrow={}", stationId, escrowAddress);
    }

    public void deregisterEscrow(String stationId) {
        escrowRegistry.remove(stationId);
    }

    @EventListener
    public void onAnomaly(AnomalyEvent event) {

        log.warn("ðŸš¨ Anomaly received: stationId={} severity={}",
                event.stationId(), event.severity());

        switch (event.severity()) {

            case LOW -> log.info("LOW â†’ ignore");

            case MEDIUM -> {
                log.warn("MEDIUM anomaly: triggering escrow refund");
                triggerRefund(event.stationId(), "MEDIUM anomaly");
            }

            case HIGH -> {
                log.error("ðŸ”¥ HIGH â†’ TRIGGER REFUND");
                triggerRefund(event.stationId(), "HIGH anomaly");
            }

            case CRITICAL -> {
                log.error("ðŸ’€ CRITICAL â†’ REFUND + QUARANTINE");
                triggerRefund(event.stationId(), "CRITICAL anomaly");
                if (shouldQuarantine(event.stationId())) {
                    digitalTwinService.quarantineStation(event.stationId(), "CRITICAL anomaly");
                }
            }
        }
    }

    private boolean shouldQuarantine(String stationId) {
        return digitalTwinService.getTwin(stationId)
            .map(twin -> twin.getStatus() != com.cybersecuals.gridgarrison.watchdog.service.StationTwin.TwinStatus.QUARANTINED)
            .orElse(true);
    }

    private void triggerRefund(String stationId, String reason) {

        String escrowAddress = escrowRegistry.get(stationId);

        if (escrowAddress == null) {
            log.error("âŒ NO ESCROW FOUND â†’ stationId={} (THIS IS WHY GANACHE SHOWS NOTHING)", stationId);
            return;
        }

        log.error("ðŸš¨ CALLING REFUND â†’ escrow={}", escrowAddress);

        escrowService.refundSession(escrowAddress, reason)
                .whenComplete((txHash, err) -> {
                    if (err != null) {
                        log.error("âŒ REFUND FAILED", err);
                    } else {
                        log.error("âœ… REFUND SUCCESS â†’ txHash={}", txHash);
                        deregisterEscrow(stationId);
                    }
                });
    }
}
