package com.cybersecuals.gridgarrison;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * GridGarrison — EV Charging Trust & Identity Platform
 *
 * Module layout:
 *  ├── orchestrator  → OCPP 2.0.1 WebSocket gateway + mTLS enforcement
 *  ├── trust         → Blockchain / Golden Hash verification (Web3j)
 *  ├── watchdog      → Digital Twin behavioural anomaly detection
 *  └── shared        → Public DTOs shared across module boundaries
 */
@SpringBootApplication
@Modulithic(systemName = "GridGarrison", sharedModules = "shared")
public class GridGarrisonApplication {

    public static void main(String[] args) {
        SpringApplication.run(GridGarrisonApplication.class, args);
    }
}
