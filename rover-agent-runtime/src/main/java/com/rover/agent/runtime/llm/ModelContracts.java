package com.rover.agent.runtime.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 可回放的应用契约版本；供应商真实模型版本未知时不猜测。 */
public final class ModelContracts {
    public static final String POLICY = "model-policy-v2";
    public static final String TARGET = "target-v1";
    public static final String REPORT = "diagnostic-facts-v2";
    public static final String CONVERSATION = "conversation-tools-v2";
    private ModelContracts() { }
    public static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] data = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array()); digest.update(data);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}

