package com.rover.gateway.core.config;

import com.rover.common.config.ConfigItem;
import com.rover.common.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable receipt for the one narrowly allowlisted Agent configuration action. */
public final class GatewayConfigActionStore {
    public static final String TRACE_SAMPLE_RATE = GatewayRuntimeConfigKeys.TRACE_SAMPLE_RATE;
    private static final java.util.Set<String> ALLOWED_VALUES = java.util.Set.of("0", "1");
    private final String url;
    private final GatewayRuntimeConfigManager configs;

    public GatewayConfigActionStore(Path routeOverlay, GatewayRuntimeConfigManager configs) {
        Path database = routeOverlay.toAbsolutePath().resolveSibling(routeOverlay.getFileName() + ".config-actions");
        // The write-ahead intent must reach durable storage before the config overlay is changed.
        // H2's default delayed write window could otherwise lose PREPARED after a host crash.
        this.url = "jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000";
        this.configs = java.util.Objects.requireNonNull(configs);
        try (Connection c = open(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS gateway_config_action(operation_id VARCHAR(128) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL, config_key VARCHAR(128) NOT NULL, expected_value VARCHAR(256) NOT NULL, desired_value VARCHAR(256) NOT NULL, actor VARCHAR(128) NOT NULL, status VARCHAR(32) NOT NULL, result_json CLOB, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
        } catch (Exception ex) { throw new IllegalStateException("初始化配置动作回执库失败", ex); }
    }

    public synchronized Receipt execute(String operationId, String key, String expected, String desired, String actor) {
        validate(operationId, key, expected, desired, actor);
        String digest = requestHash(key, expected, desired, actor);
        Receipt existing = find(operationId);
        if (existing != null) {
            if (!existing.requestHash().equals(digest)) { throw new IdempotencyConflictException(operationId); }
            return observe(existing).asReplay();
        }
        long now = System.currentTimeMillis();
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("INSERT INTO gateway_config_action(operation_id,request_hash,config_key,expected_value,desired_value,actor,status,created_at,updated_at) VALUES(?,?,?,?,?,?, 'PREPARED',?,?)")) {
            p.setString(1, operationId); p.setString(2, digest); p.setString(3, key); p.setString(4, expected);
            p.setString(5, desired); p.setString(6, actor); p.setLong(7, now); p.setLong(8, now); p.executeUpdate();
        } catch (Exception ex) { throw new IllegalStateException("配置动作意图持久化失败，未提交", ex); }
        Receipt prepared = find(operationId);
        boolean changed;
        try {
            changed = configs.updateTraceSampleRateIfCurrent(prepared.expectedValue(), prepared.desiredValue());
        } catch (RuntimeException failure) {
            return prepared.withStatus("UNKNOWN", Map.of("operationId", prepared.operationId(),
                    "key", prepared.configKey(), "status", "UNKNOWN",
                    "message", "配置更新返回异常，运行时或持久值可能已部分改变；不会自动重试，需人工核对"));
        }
        if (!changed) {
            return settle(prepared, "PRECONDITION_FAILED", "配置版本已变化，未覆盖当前值");
        }
        // If the receipt write fails after the config changed, leave PREPARED for read-only
        // reconciliation. The caller must query with this operationId; never infer from value.
        return settle(prepared, "APPLIED", "配置已生效并持久化");
    }

    public synchronized Receipt query(String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{1,128}")) { throw new IllegalArgumentException("operationId 无效"); }
        Receipt receipt = find(operationId);
        return receipt == null ? null : observe(receipt);
    }

    /** Pending operations are only observed after restart; queries and retries never issue writes.
     * The config value alone cannot prove which writer produced it, so PREPARED is never promoted
     * to a terminal result by observation (even when it currently equals the desired value).
     */
    private Receipt observe(Receipt receipt) {
        if (!"PREPARED".equals(receipt.status())) { return receipt; }
        String observedValue = currentValue(receipt.configKey());
        return receipt.withStatus("UNKNOWN", Map.of("operationId", receipt.operationId(),
                "key", receipt.configKey(), "status", "UNKNOWN", "observedValue", observedValue,
                "expectedValue", receipt.expectedValue(), "desiredValue", receipt.desiredValue(),
                "message", "操作意图已保存但最终回执缺失；仅凭当前配置无法证明写入归属，不会自动重放"));
    }

    private Receipt settle(Receipt receipt, String status, String message) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("operationId", receipt.operationId()); result.put("key", receipt.configKey());
        result.put("value", receipt.desiredValue()); result.put("status", status); result.put("message", message);
        long now = System.currentTimeMillis();
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("UPDATE gateway_config_action SET status=?,result_json=?,updated_at=? WHERE operation_id=? AND request_hash=? AND status='PREPARED'")) {
            p.setString(1, status); p.setString(2, JsonCodec.toJson(result)); p.setLong(3, now);
            p.setString(4, receipt.operationId()); p.setString(5, receipt.requestHash()); p.executeUpdate();
        } catch (Exception ex) { throw new IllegalStateException("配置动作回执落库失败；请使用原 operationId 查询", ex); }
        return new Receipt(receipt.operationId(), receipt.requestHash(), receipt.configKey(), receipt.expectedValue(),
                receipt.desiredValue(), receipt.actor(), status, result, receipt.createdAtMillis(), now, false);
    }

    private Receipt find(String operationId) {
        try (Connection c = open(); PreparedStatement p = c.prepareStatement("SELECT * FROM gateway_config_action WHERE operation_id=?")) {
            p.setString(1, operationId); try (ResultSet r = p.executeQuery()) {
                if (!r.next()) { return null; }
                Map<String,Object> result = r.getString(8) == null ? Map.of() : JsonCodec.parseObjectMap(r.getString(8));
                return new Receipt(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), result, r.getLong(9), r.getLong(10), false);
            }
        } catch (Exception ex) { throw new IllegalStateException("读取配置动作回执失败", ex); }
    }

    private String currentValue(String key) {
        return configs.listConfigs().stream().filter(item -> item.getKey().equals(key))
                .map(ConfigItem::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("白名单配置项未注册"));
    }

    private Connection open() throws Exception { return DriverManager.getConnection(url, "sa", ""); }
    private static void validate(String operationId, String key, String expected, String desired, String actor) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{1,128}") || !TRACE_SAMPLE_RATE.equals(key)
                || !validExpected(expected) || !ALLOWED_VALUES.contains(desired) || Double.parseDouble(expected) == Double.parseDouble(desired)
                || actor == null || actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("仅允许对 trace.sampleRate 使用明确的 0/1 变更，并提供不同旧值与新值、操作号和操作者");
        }
    }
    private static boolean validExpected(String value) {
        if (value == null || value.length() > 32) return false;
        try { double rate = Double.parseDouble(value); return Double.isFinite(rate) && (rate == 0 || rate == 1); }
        catch (NumberFormatException invalid) { return false; }
    }
    static String requestHash(String key, String expected, String desired, String actor) {
        return hash(key + "|" + expected + "|" + desired + "|" + actor);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("JVM 不支持 SHA-256", ex); }
    }
    public record Receipt(String operationId, String requestHash, String configKey, String expectedValue, String desiredValue,
                          String actor, String status, Map<String,Object> result, long createdAtMillis, long updatedAtMillis, boolean replayed) {
        public Receipt { result = Map.copyOf(result); }
        Receipt asReplay() { return new Receipt(operationId, requestHash, configKey, expectedValue, desiredValue, actor, status, result, createdAtMillis, updatedAtMillis, true); }
        Receipt withStatus(String value, Map<String,Object> body) { return new Receipt(operationId, requestHash, configKey, expectedValue, desiredValue, actor, value, body, createdAtMillis, updatedAtMillis, false); }
    }

    public static final class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException(String operationId) {
            super("operationId 已用于不同配置意图：" + operationId);
        }
    }
}
