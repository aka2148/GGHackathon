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

    // ✅ REGISTER ESCROW (CRITICAL FOR REFUND TO WORK)
    public void registerEscrow(String stationId, String escrowAddress) {
        escrowRegistry.put(stationId, escrowAddress);
        log.info("✅ Escrow registered: stationId={} escrow={}", stationId, escrowAddress);
    }

    public void deregisterEscrow(String stationId) {
        escrowRegistry.remove(stationId);
    }

    @EventListener
    public void onAnomaly(AnomalyEvent event) {

        log.warn("🚨 Anomaly received: stationId={} severity={}",
                event.stationId(), event.severity());

        switch (event.severity()) {

            case LOW -> log.info("LOW → ignore");

            case MEDIUM -> log.warn("MEDIUM → warning only");

            case HIGH -> {
                log.error("🔥 HIGH → TRIGGER REFUND");
                triggerRefund(event.stationId(), "HIGH anomaly");
            }

            case CRITICAL -> {
                log.error("💀 CRITICAL → REFUND + QUARANTINE");
                triggerRefund(event.stationId(), "CRITICAL anomaly");
                digitalTwinService.quarantineStation(event.stationId(), "CRITICAL anomaly");
            }
        }
    }

    private void triggerRefund(String stationId, String reason) {

        String escrowAddress = escrowRegistry.get(stationId);

        if (escrowAddress == null) {
            log.error("❌ NO ESCROW FOUND → stationId={} (THIS IS WHY GANACHE SHOWS NOTHING)", stationId);
            return;
        }

        log.error("🚨 CALLING REFUND → escrow={}", escrowAddress);

        escrowService.refundSession(escrowAddress, reason)
                .whenComplete((txHash, err) -> {
                    if (err != null) {
                        log.error("❌ REFUND FAILED", err);
                    } else {
                        log.error("✅ REFUND SUCCESS → txHash={}", txHash);
                        deregisterEscrow(stationId);
                    }
                });
    }
}