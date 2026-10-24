package com.rover.admin.agent.adapter;

import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.GatewayConfigActionException;
import com.rover.agent.core.port.GatewayConfigActionPort;
import com.rover.agent.core.port.GatewayConfigActionReceipt;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Translates Admin's authenticated management client to the narrow Gateway config action port. */
@Component
public final class AdminGatewayConfigActionAdapter implements GatewayConfigActionPort {

    private final AdminConfigService admin;

    public AdminGatewayConfigActionAdapter(AdminConfigService admin) { this.admin = admin; }

    @Override
    public GatewayConfigActionReceipt execute(String operationId, String configKey, String expectedValue,
                                              String desiredValue) {
        if (operationId == null || operationId.isBlank() || configKey == null || configKey.isBlank()
                || expectedValue == null || desiredValue == null) {
            throw new IllegalArgumentException("operationId, configKey and immutable values are required");
        }
        try {
            GatewayConfigActionReceipt receipt = receipt(admin.executeGatewayConfigAction(operationId,
                    configKey, expectedValue, desiredValue));
            if (!matchesReceipt(operationId, configKey, expectedValue, desiredValue, receipt)) {
                throw new GatewayConfigActionException("Gateway 配置动作回执与原始意图不匹配", true, null);
            }
            return receipt;
        } catch (GatewayConfigActionException ex) {
            throw ex;
        } catch (ManageApiCallException ex) {
            // Gateway 4xx is a definitive rejection; no-response and 5xx may follow a committed write.
            throw new GatewayConfigActionException("提交 Gateway 配置动作失败: " + ex.getMessage(),
                    !ex.hasResponse() || ex.statusCode() >= 500, ex);
        } catch (RuntimeException ex) {
            throw new GatewayConfigActionException("Gateway 配置动作回执无法解析；结果未知，请按原操作号查询", true, ex);
        }
    }

    @Override
    public GatewayConfigActionReceipt query(String operationId, String configKey, String expectedValue, String desiredValue) {
        if (operationId == null || operationId.isBlank()) { throw new IllegalArgumentException("需要 operationId"); }
        try {
            Map<String, Object> body = admin.gatewayConfigAction(operationId);
            GatewayConfigActionReceipt receipt = receipt(body);
            if (Boolean.TRUE.equals(body.get("notFound"))) {
                if (!operationId.equals(receipt.operationId())
                        || receipt.status() != GatewayConfigActionReceipt.Status.UNKNOWN) {
                    throw new GatewayConfigActionException("Gateway 缺失回执标记与操作号或状态不匹配", true, null);
                }
                return receipt;
            }
            if (!matchesReceipt(operationId, configKey, expectedValue, desiredValue, receipt)) {
                throw new GatewayConfigActionException("Gateway 配置动作回执与原始意图不匹配", true, null);
            }
            return receipt;
        } catch (ManageApiCallException ex) {
            throw new GatewayConfigActionException("读取 Gateway 配置动作回执失败: " + ex.getMessage(), true, ex);
        } catch (GatewayConfigActionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GatewayConfigActionException("读取 Gateway 配置动作回执无法解析", true, ex);
        }
    }

    private static GatewayConfigActionReceipt receipt(Map<String, Object> body) {
        String operationId = text(body.get("operationId"));
        if (operationId.isBlank()) throw new IllegalArgumentException("Gateway 配置回执缺少 operationId");
        GatewayConfigActionReceipt.Status status;
        try { status = GatewayConfigActionReceipt.Status.valueOf(text(body.get("status"))); }
        catch (IllegalArgumentException ex) { status = GatewayConfigActionReceipt.Status.UNKNOWN; }
        return new GatewayConfigActionReceipt(operationId, text(body.get("key")), text(body.get("expectedValue")),
                text(body.get("desiredValue")), status, Boolean.TRUE.equals(body.get("replayed")),
                text(body.get("message")));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean matchesRequired(String expected, String actual) {
        return expected != null && !expected.isBlank() && actual != null && expected.equals(actual);
    }
    private static boolean matchesReceipt(String operationId, String key, String expected, String desired,
                                          GatewayConfigActionReceipt receipt) {
        if (receipt == null || !operationId.equals(receipt.operationId())) return false;
        return matchesRequired(key, receipt.configKey())
                && matchesRequired(expected, receipt.expectedValue())
                && matchesRequired(desired, receipt.desiredValue());
    }
}
