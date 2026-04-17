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
        RuntimeTraceService.TrustEvidenceView trustEvidence = extractTrustEvidence(event);
        String severity = determineSeverity(type, trustEvidence);
        String summary = buildSummary(rawPayload, trustEvidence);

        runtimeTraceService.appendEvent(
            module,
            type,
            stationId,
            summary,
            severity,
            trustEvidence
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

    private Object readRecordObject(Object event, String accessorName) {
        try {
            Method method = event.getClass().getDeclaredMethod(accessorName);
            method.setAccessible(true);
            return method.invoke(event);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private RuntimeTraceService.TrustEvidenceView extractTrustEvidence(Object event) {
        Object evidence = readRecordObject(event, "evidence");
        if (evidence == null) {
            return null;
        }

        return new RuntimeTraceService.TrustEvidenceView(
            readRecordValue(evidence, "stationId"),
            readRecordValue(evidence, "verdict"),
            readRecordValue(evidence, "reportedHash"),
            readRecordValue(evidence, "expectedHash"),
            readRecordValue(evidence, "manufacturerId"),
            readRecordValue(evidence, "manufacturerSignature"),
            parseBoolean(readRecordValue(evidence, "signatureVerified")),
            readRecordValue(evidence, "contractAddress"),
            readRecordValue(evidence, "txHash"),
            readRecordValue(evidence, "rpcStatus"),
            parseInstant(readRecordValue(evidence, "observedAt")),
            readRecordValue(evidence, "rationale"),
            parseLong(readRecordValue(evidence, "latencyMs")),
            readRecordValue(evidence, "latencyBand")
        );
    }

    private String buildSummary(String rawPayload, RuntimeTraceService.TrustEvidenceView trustEvidence) {
        if (trustEvidence != null) {
            String verdict = trustEvidence.verdict() == null ? "UNKNOWN" : trustEvidence.verdict();
            String rationale = trustEvidence.rationale() == null ? "No rationale" : trustEvidence.rationale();
            String rpcStatus = trustEvidence.rpcStatus() == null ? "UNKNOWN" : trustEvidence.rpcStatus();
            return abbreviate("Verdict=" + verdict + " | RPC=" + rpcStatus + " | " + rationale);
        }
        return abbreviate(rawPayload == null ? "No payload" : rawPayload);
    }

    private String determineSeverity(String type,
                                     RuntimeTraceService.TrustEvidenceView trustEvidence) {
        if (type.contains("Security")) {
            return "WARN";
        }

        if (trustEvidence == null || trustEvidence.verdict() == null) {
            return "INFO";
        }

        String verdict = trustEvidence.verdict().toUpperCase();
        if ("VERIFIED".equals(verdict)) {
            return "INFO";
        }
        return "WARN";
    }

    private java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    private String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }
}
