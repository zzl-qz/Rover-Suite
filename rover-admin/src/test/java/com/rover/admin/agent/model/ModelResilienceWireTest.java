package com.rover.admin.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.runtime.llm.*;
import com.rover.agent.runtime.task.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SDK 与回环 HTTP 错误，不依赖公网模型；验证状态分类不是靠异常文案猜测。 */
class ModelResilienceWireTest {
    @ParameterizedTest @ValueSource(ints={400,401,429,503})
    void sdkErrorsAreIsolatedAndBodiesNeverPersisted(int code) throws Exception {
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();byte[] body="{\"error\":{\"message\":\"provider-secret\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(code,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try {
            var gateway=gateway();gateway.apply(settings(server,"main",FastModel.none()));var first=new AgentRunBudget(AgentRunLimits.defaults());
            assertThrows(RuntimeException.class,()->call(gateway,ChatModelGateway.Purpose.PLANNING,first));
            String expected=switch(code){case 400->"INVALID_REQUEST";case 401->"AUTHENTICATION";case 429->"RATE_LIMITED";default->"SERVER_ERROR";};
            assertEquals(expected,first.modelInvocations().get(0).status());assertFalse(new ObjectMapper().writeValueAsString(first.snapshot()).contains("provider-secret"));
            var second=new AgentRunBudget(AgentRunLimits.defaults());assertThrows(RuntimeException.class,()->call(gateway,ChatModelGateway.Purpose.LIGHTWEIGHT,second));
            assertEquals(code==400 ? 2 : 1,requests.get(),"同一端点的不同场景共用故障隔离，参数错误不拖累其它场景");
            if(code!=400) { assertEquals("CIRCUIT_OPEN",second.modelInvocations().get(0).status());assertEquals(0,second.snapshot().modelCalls()); }
        } finally { server.stop(0); }
    }

    @Test void requestedReturnedAndHotReloadedModelsAreRecordedSeparately() throws Exception {
        List<String> requested=new java.util.concurrent.CopyOnWriteArrayList<>();ObjectMapper json=new ObjectMapper();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{String model=json.readTree(e.getRequestBody()).path("model").asText();requested.add(model);
            byte[] bytes=("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\""+model+"-resolved\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try {
            var gateway=gateway();var fast=new FastModel("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","fast");
            gateway.apply(settings(server,"main",fast));var old=gateway.select(ChatModelGateway.Purpose.PLANNING,15,false);
            var lightweight=new AgentRunBudget(AgentRunLimits.defaults());call(gateway,ChatModelGateway.Purpose.LIGHTWEIGHT,lightweight);
            assertEquals("fast",lightweight.modelInvocations().get(0).identity().requestedModel());assertEquals("fast-resolved",lightweight.modelInvocations().get(0).returnedModel());
            gateway.apply(settings(server,"main-new",fast));var budget=new AgentRunBudget(AgentRunLimits.defaults());
            AgentBudgetAdvisor.client(old,budget,gateway::reasoningDelta,"planning",ModelContracts.PLANNING).prompt().user("fixture").call().content();
            assertEquals("main",budget.modelInvocations().get(0).identity().requestedModel());assertEquals("main-resolved",budget.modelInvocations().get(0).returnedModel());
            assertEquals(List.of("fast","main"),requested);
        } finally { server.stop(0); }
    }
    private static AdminChatModelGateway gateway() { return new AdminChatModelGateway(null,new DefaultListableBeanFactory().getBeanProvider(ToolCallingManager.class),AgentRunLimits.defaults(),new ProviderIsolation.Policy(1,30,4)); }

    @Test void glmAdapterPreservesTypedStatusAndDiscardsUntrustedErrorBody() throws Exception {
        AtomicInteger requests=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();byte[] bytes="{\"error\":\"provider-secret\"}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","application/json");e.getResponseHeaders().set("Retry-After","120");e.sendResponseHeaders(429,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try {
            var gateway=new AdminChatModelGateway(null,new DefaultListableBeanFactory().getBeanProvider(ToolCallingManager.class),AgentRunLimits.defaults(),new ProviderIsolation.Policy(1,30,4)) {
                @Override protected org.springframework.ai.chat.client.ChatClient build(ModelSettings settings,boolean thinking) {
                    var local=new ModelSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),"fixture",settings.model(),15,ModelSettings.Source.FILE,ModelSettings.KeyState.OK);
                    org.springframework.ai.chat.model.ChatModel model=org.springframework.test.util.ReflectionTestUtils.invokeMethod(this,"thinkingCapableModel",local,thinking);
                    return org.springframework.ai.chat.client.ChatClient.create(model);
                }
            };
            gateway.apply(new ModelSettings(true,"https://open.bigmodel.cn/api/paas/v4","fixture","glm-4.6",15,ModelSettings.Source.FILE,ModelSettings.KeyState.OK));
            var budget=new AgentRunBudget(AgentRunLimits.defaults());assertThrows(RuntimeException.class,()->call(gateway,ChatModelGateway.Purpose.PLANNING,budget));
            assertEquals("RATE_LIMITED",budget.modelInvocations().get(0).status());assertFalse(new ObjectMapper().writeValueAsString(budget.snapshot()).contains("provider-secret"));
            assertThrows(RuntimeException.class,()->call(gateway,ChatModelGateway.Purpose.LIGHTWEIGHT,new AgentRunBudget(AgentRunLimits.defaults())));assertEquals(1,requests.get());
            gateway.apply(new ModelSettings(true,"https://open.bigmodel.cn/api/paas/v4","fixture","glm-4.6",15,ModelSettings.Source.FILE,ModelSettings.KeyState.OK));
            var streaming=new AgentRunBudget(AgentRunLimits.defaults());
            var streamFailure=assertThrows(RuntimeException.class,()->AgentBudgetAdvisor.client(gateway.select(ChatModelGateway.Purpose.CONVERSATION,15,true),streaming,gateway::reasoningDelta,"stream",ModelContracts.CONVERSATION)
                    .prompt().user("fixture").stream().chatResponse().blockLast(java.time.Duration.ofSeconds(10)));
            assertEquals("RATE_LIMITED",streaming.modelInvocations().get(0).status());
            assertEquals(120,ModelFailures.retryAfterSeconds(streamFailure),"流式 SDK 的结构化 Retry-After 同样必须保留");
            assertEquals(2,requests.get(),"流式 SDK 失败不能在任务预算之外暗中重试");
        } finally {server.stop(0);}
    }
    private static ModelSettings settings(HttpServer server,String model,FastModel fast) { return new ModelSettings(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture",model,15,ModelSettings.Source.FILE,ModelSettings.KeyState.OK,fast); }
    private static void call(AdminChatModelGateway gateway,ChatModelGateway.Purpose purpose,AgentRunBudget budget) {
        AgentBudgetAdvisor.client(gateway.select(purpose,15,false),budget,gateway::reasoningDelta,"wire",ModelContracts.PLANNING).prompt().user("fixture").call().content();
    }
}
