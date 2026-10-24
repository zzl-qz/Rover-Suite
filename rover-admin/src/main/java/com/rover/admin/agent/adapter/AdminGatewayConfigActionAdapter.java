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
                    configKey, expectedValue, desiredValue), operationId);
            if (!operationId.equals(receipt.operationId())
                    || !configKey.equals(receipt.configKey())
                    || !expectedValue.equals(receipt.expectedValue())
                    || !desiredValue.equals(receipt.desiredValue())) {
                throw new GatewayConfigActionException("Gateway 配置动作回执与原始意图不匹配", true, null);
            }
            return receipt;
        } catch (ManageApiCallException ex) {
            // Gateway 4xx is a definitive rejection; no-response and 5xx may follow a committed write.
            throw new GatewayConfigActionException("提交 Gateway 配置动作失败: " + ex.getMessage(),
                    !ex.hasResponse() || ex.statusCode() >= 500, ex);
        }
    }

    @Override
    public GatewayConfigActionReceipt query(String operationId) {
        if (operationId == null || operationId.isBlank()) { throw new IllegalArgumentException("需要 operationId"); }
        try {
            GatewayConfigActionReceipt receipt = receipt(admin.gatewayConfigAction(operationId), operationId);
            if (!operationId.equals(receipt.operationId())) {
                throw new GatewayConfigActionException("Gateway 配置动作回执 operationId 不匹配", true, null);
            }
            return receipt;
        } catch (ManageApiCallException ex) {
            throw new GatewayConfigActionException("读取 Gateway 配置动作回执失败: " + ex.getMessage(), true, ex);
        }
    }

    private static GatewayConfigActionReceipt receipt(Map<String, Object> body, String fallbackOperationId) {
        String operationId = text(body.get("operationId"));
        if (operationId.isBlank()) { operationId = fallbackOperationId; }
        GatewayConfigActionReceipt.Status status;
        try { status = GatewayConfigActionReceipt.Status.valueOf(text(body.get("status"))); }
        catch (IllegalArgumentException ex) { status = GatewayConfigActionReceipt.Status.UNKNOWN; }
        return new GatewayConfigActionReceipt(operationId, text(body.get("key")), text(body.get("expectedValue")),
                text(body.get("desiredValue")), status, Boolean.TRUE.equals(body.get("replayed")),
                text(body.get("message")));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
