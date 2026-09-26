package com.rover.admin.agent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 模型热切换：三态语义、构建失败保留上一个可用客户端、密钥不可读必须被看见。 */
class AdminChatModelGatewayTest {

    @Test
    void startsUnconfiguredAndBecomesAvailableAfterApply() {
        AdminChatModelGateway gateway = newGateway();

        assertFalse(gateway.configured());
        assertFalse(gateway.available());
        assertEquals("未配置模型", gateway.description());
        assertThrows(IllegalStateException.class, gateway::chatClient);

        gateway.apply(settings(true, "https://api.deepseek.com", "sk-abcdefghijklmnop", "deepseek-chat"));

        assertTrue(gateway.configured());
        assertTrue(gateway.available());
        // 真正构建出 ChatClient 才能证明 OpenAI 兼容链路的类都在（含 okhttp）。
        assertNotNull(gateway.chatClient());
        assertEquals("deepseek-chat @ api.deepseek.com", gateway.description());
        assertEquals(1L, gateway.buildId());
        assertNotNull(gateway.appliedAt());
        assertNull(gateway.lastError());
    }

    @Test
    void disablingReturnsToUnconfigured() {
        AdminChatModelGateway gateway = newGateway();
        gateway.apply(settings(true, "http://127.0.0.1:11434/v1", "", "qwen2.5:7b"));
        assertTrue(gateway.available());

        gateway.apply(settings(false, "http://127.0.0.1:11434/v1", "", "qwen2.5:7b"));

        // 明确停用：不能因为上一次可用就继续谎报。
        assertFalse(gateway.configured());
        assertFalse(gateway.available());
        assertThrows(IllegalStateException.class, gateway::chatClient);
    }

    @Test
    void unreadableKeyIsConfiguredButNotAvailable() {
        AdminChatModelGateway gateway = newGateway();

        gateway.apply(new ModelSettings(true, "https://api.openai.com", "", "gpt-4o-mini",
                ModelSettings.DEFAULT_TIMEOUT_SECONDS, ModelSettings.Source.FILE,
                ModelSettings.KeyState.UNREADABLE));

        assertTrue(gateway.configured());
        assertFalse(gateway.available());
        assertEquals("API 密钥无法解密，请重新填写", gateway.lastError());
        assertThrows(IllegalStateException.class, gateway::chatClient);
    }

    @Test
    void keepsPreviousClientWhenApplyFailsToBuild() {
        AdminChatModelGateway gateway = new AdminChatModelGateway(mock(ModelConfigStore.class), toolManagers()) {
            @Override
            protected ChatClient build(ModelSettings settings) {
                if (settings.baseUrl().startsWith("boom")) {
                    throw new IllegalStateException("模拟构建失败");
                }
                return super.build(settings);
            }
        };
        gateway.apply(settings(true, "https://api.deepseek.com", "sk-abcdefghijklmnop", "deepseek-chat"));
        long buildId = gateway.buildId();

        gateway.apply(settings(true, "boom://broken", "sk-abcdefghijklmnop", "deepseek-chat"));

        // 构建失败不能把已经能用的诊断链弄坏：客户端与生效版本都保持在上一次成功的那份。
        assertTrue(gateway.available());
        assertNotNull(gateway.chatClient());
        assertEquals(buildId, gateway.buildId());
        assertEquals("deepseek-chat @ api.deepseek.com", gateway.description());
        assertNotNull(gateway.lastError());
        assertFalse(gateway.lastError().contains("sk-abcdefghijklmnop"));
    }

    @Test
    void maskedKeyNeverLeaksThroughDescription() {
        AdminChatModelGateway gateway = newGateway();
        gateway.apply(settings(true, "https://api.openai.com", "sk-abcdefghijklmnop", "gpt-4o-mini"));

        assertFalse(gateway.description().contains("sk-abcdefghijklmnop"));
    }

    @Test
    void sceneTimeoutClientIsCachedAndOnlyTightensTheConfiguredTimeout() {
        AdminChatModelGateway gateway = newGateway();
        gateway.apply(settings(true, "https://api.deepseek.com", "sk-abcdefghijklmnop", "deepseek-chat"));

        // 配置 30 秒：更短的场景上限才另建客户端并复用；0 或不小于配置值的请求都用主客户端
        ChatClient main = gateway.chatClient();
        ChatClient quick = gateway.chatClient(10);
        assertNotSame(main, quick);
        assertSame(quick, gateway.chatClient(10));
        assertSame(main, gateway.chatClient(0));
        assertSame(main, gateway.chatClient(30));

        // 配置再次生效：按旧配置建出的场景客户端一律作废
        gateway.apply(settings(true, "http://127.0.0.1:11434/v1", "", "qwen2.5:7b"));
        assertNotSame(quick, gateway.chatClient(10));
    }

    private static ModelSettings settings(boolean enabled, String baseUrl, String apiKey, String model) {
        return new ModelSettings(enabled, baseUrl, apiKey, model, ModelSettings.DEFAULT_TIMEOUT_SECONDS,
                ModelSettings.Source.FILE, ModelSettings.keyStateOf(apiKey));
    }

    private static AdminChatModelGateway newGateway() {
        return new AdminChatModelGateway(mock(ModelConfigStore.class), toolManagers());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ToolCallingManager> toolManagers() {
        ObjectProvider<ToolCallingManager> managers = mock(ObjectProvider.class);
        when(managers.getIfAvailable(any(Supplier.class))).thenReturn(DefaultToolCallingManager.builder().build());
        return managers;
    }
}