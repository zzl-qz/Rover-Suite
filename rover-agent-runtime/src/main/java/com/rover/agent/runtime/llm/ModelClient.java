package com.rover.agent.runtime.llm;

import com.rover.agent.core.model.ModelIdentity;
import org.springframework.ai.chat.client.ChatClient;

/** 客户端与其身份必须一次获取，配置热更新不能令审计记到另一台模型上。 */
public record ModelClient(ChatClient client,ModelIdentity identity,ProviderIsolation isolation,String isolationKey) {
    @Override public String toString() { return "ModelClient["+identity+"]"; }
}
