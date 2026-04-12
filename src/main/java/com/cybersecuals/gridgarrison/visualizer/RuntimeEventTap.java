package com.cybersecuals.gridgarrison.visualizer;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
class RuntimeEventTap {

    private static final int MAX_SUMMARY_LENGTH = 180;

    private final RuntimeTraceService runtimeTraceService;

    RuntimeEventTap(RuntimeTraceService runtimeTraceService) {
        this.runtimeTraceService = runtimeTraceService;
    }

    @EventListener
    public void onEvent(Object event) {
        Class<?> eventClass = event.getClass();
        String pkg = eventClass.getPackageName();
        if (!pkg.startsWith("com.cybersecuals.gridgarrison")) {
            return;
        }

        String type = eventClass.getSimpleName();
        if (!type.endsWith("Event")) {
            return;
        }

        String stationId = readRecordValue(event, "stationId");
        String rawPayload = readRecordValue(event, "rawPayload");
        String module = moduleFromPackage(pkg);
        String severity = type.contains("Security") ? "WARN" : "INFO";

        runtimeTraceService.appendEvent(
            module,
            type,
            stationId,
            abbreviate(rawPayload == null ? "No payload" : rawPayload),
            severity
        );
    }

    private String moduleFromPackage(String pkg) {
        if (pkg.contains(".orchestrator.")) {
            return "orchestrator";
        }
        if (pkg.contains(".trust.")) {
            return "trust";
        }
        if (pkg.contains(".watchdog.")) {
            return "watchdog";
        }
        if (pkg.contains(".shared.")) {
            return "shared";
        }
        return "app";
    }

    private String readRecordValue(Object event, String accessorName) {
        try {
            Method method = event.getClass().getDeclaredMethod(accessorName);
            method.setAccessible(true);
            Object value = method.invoke(event);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }
}
