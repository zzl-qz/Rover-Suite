package com.rover.agent.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, deliberately narrow configuration intent. */
public record ConfigActionIntent(String configKey, String beforeValue, String desiredValue,
                                 ResourceScope scope, String controlTargetId, String targetNamespace) {
    public static final String ALLOWED_KEY = "gateway.trace.sampleRate";
    public static final String CONTRACT_VERSION = "gateway-config-intent-v1";

    public ConfigActionIntent {
        if (!ALLOWED_KEY.equals(configKey) || !validSampleRate(beforeValue) || !binary(desiredValue)
                || decimal(beforeValue) == decimal(desiredValue)) {
            throw new IllegalArgumentException("仅允许在 0 与 1 之间调整 Gateway 链路采样率");
        }
        Objects.requireNonNull(scope, "scope");
        controlTargetId = required(controlTargetId, "controlTargetId", 128);
        targetNamespace = required(targetNamespace, "targetNamespace", 128);
    }

    public String intentHash() {
        String canonical = String.join("\n", CONTRACT_VERSION, configKey, beforeValue, desiredValue,
                scope.organizationId(), scope.teamId(), scope.environmentId(), controlTargetId, targetNamespace);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean binary(String value) { return "0".equals(value) || "1".equals(value); }
    private static boolean validSampleRate(String value) {
        if (value == null || value.length() > 32) return false;
        try { double rate = Double.parseDouble(value); return Double.isFinite(rate) && (rate == 0 || rate == 1); }
        catch (NumberFormatException invalid) { return false; }
    }
    private static double decimal(String value) { return Double.parseDouble(value); }
    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
