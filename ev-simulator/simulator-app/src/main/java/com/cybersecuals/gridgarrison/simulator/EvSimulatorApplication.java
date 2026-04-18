package com.cybersecuals.gridgarrison.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dummy EV Simulator — pretends to be an EV charger station.
 *
 * Connects to GridGarrison via WebSocket over WSS with client certificate.
 * Streams OCPP 2.0.1 events: boot, heartbeat, transaction start/end, firmware status.
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
public class EvSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvSimulatorApplication.class, args);
    }

    @Bean
    public ApplicationRunner simulatorRunner(EvWebSocketClient client) {
        return args -> {
            log.info("🚗 EV Simulator starting...");
            client.connectWithBackoff();
        };
    }
}
