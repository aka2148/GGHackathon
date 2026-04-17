package com.cybersecuals.gridgarrison.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ev.simulator")
public class EvTelemetryProfileProperties {

    private String activeProfile = "normal";
    private Map<String, TelemetryProfile> profiles = new LinkedHashMap<>();

    public String getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(String activeProfile) {
        this.activeProfile = activeProfile;
    }

    public synchronized boolean setActiveProfileIfExists(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return false;
        }
        if (!profiles.containsKey(profileName)) {
            return false;
        }
        this.activeProfile = profileName;
        return true;
    }

    public Map<String, TelemetryProfile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, TelemetryProfile> profiles) {
        this.profiles = profiles;
    }

    public List<String> getAvailableProfileNames() {
        List<String> names = new ArrayList<>(profiles.keySet());
        Collections.sort(names);
        return names;
    }

    public TelemetryProfile resolveActiveProfile() {
        TelemetryProfile selected = profiles.get(activeProfile);
        if (selected != null) {
            return selected;
        }

        TelemetryProfile fallback = profiles.get("normal");
        if (fallback != null) {
            return fallback;
        }

        TelemetryProfile defaults = new TelemetryProfile();
        defaults.setMeterUpdateIntervalMs(5000);
        defaults.setSessionDurationMs(60000);
        defaults.setEnergyPerUpdateKwh(1.5);
        defaults.setHeartbeatIntervalMs(30000);
        return defaults;
    }

    public static class TelemetryProfile {
        private long heartbeatIntervalMs = 30000;
        private long meterUpdateIntervalMs = 5000;
        private long sessionDurationMs = 60000;
        private double energyPerUpdateKwh = 1.5;

        public long getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }

        public long getMeterUpdateIntervalMs() {
            return meterUpdateIntervalMs;
        }

        public void setMeterUpdateIntervalMs(long meterUpdateIntervalMs) {
            this.meterUpdateIntervalMs = meterUpdateIntervalMs;
        }

        public long getSessionDurationMs() {
            return sessionDurationMs;
        }

        public void setSessionDurationMs(long sessionDurationMs) {
            this.sessionDurationMs = sessionDurationMs;
        }

        public double getEnergyPerUpdateKwh() {
            return energyPerUpdateKwh;
        }

        public void setEnergyPerUpdateKwh(double energyPerUpdateKwh) {
            this.energyPerUpdateKwh = energyPerUpdateKwh;
        }
    }
}
