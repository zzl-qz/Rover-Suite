package com.rover.admin.agent.model;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.rover.agent.runtime.llm.AgentBudgetAdvisor;
import com.rover.agent.runtime.task.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ModelWireContractTest {
    @Test void planningCanWaitBeyondTenSecondsAndUsesMainModelEvenWhenFastModelIsConfigured() throws Exception {
        ObjectMapper json=new ObjectMapper();
        List<JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var body=json.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            if(requests.size()==1) {
                try { Thread.sleep(11_000); } catch(InterruptedException stop) { Thread.currentThread().interrupt(); }
            }
            byte[] response=("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"glm-4.6\","
                    +"\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var gateway=new AdminChatModelGateway(null,new DefaultListableBeanFactory().getBeanProvider(ToolCallingManager.class)) {
                @Override protected ChatClient build(ModelSettings settings,boolean thinking) {
                    var local=new ModelSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),"fixture",settings.model(),
                            settings.timeoutSeconds(),ModelSettings.Source.FILE,ModelSettings.KeyState.OK);
                    ChatModel model=ReflectionTestUtils.invokeMethod(this,"thinkingCapableModel",local,thinking);
                    return ChatClient.builder(model).build();
                }
            };
            gateway.apply(new ModelSettings(true,"https://open.bigmodel.cn/api/paas/v4","fixture","glm-4.6",60,
                    ModelSettings.Source.FILE,ModelSettings.KeyState.OK,new FastModel("https://fast.invalid","fixture","glm-4.5")));
            long start=System.nanoTime();
            assertTrue(com.rover.agent.runtime.llm.SpringAiJsonCompletion.investigation(gateway,
                    com.rover.agent.runtime.metrics.AgentMetrics.NOOP,60,2048,false).complete("fixture","fixture",java.util.Optional::of).isPresent());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()>=10);
            assertEquals("glm-4.6",requests.get(0).path("model").asText());
            assertEquals(2048,requests.get(0).path("max_tokens").asInt());
            assertTrue(com.rover.agent.runtime.llm.SpringAiJsonCompletion.investigation(gateway,
                    com.rover.agent.runtime.metrics.AgentMetrics.NOOP,60,2048,true).complete("fixture","fixture",java.util.Optional::of).isPresent());
            assertEquals("enabled",requests.get(1).path("thinking").path("type").asText());
            assertEquals("glm-4.6",requests.get(1).path("model").asText());
            gateway.chatClient(10).prompt().user("fixture").call().content();
            assertEquals("glm-4.5",requests.get(2).path("model").asText());
            assertEquals("disabled",requests.get(2).path("thinking").path("type").asText());
            assertSame(gateway.planningClient(60,false),gateway.planningClient(90,false));
            assertNotSame(gateway.planningClient(60,false),gateway.planningClient(60,true));
            var before=gateway.planningClient(60,false);
            gateway.apply(gateway.appliedSettings());
            assertNotSame(before,gateway.planningClient(60,false));
        } finally { server.stop(0); }
    }
    @Test void wirePayloadPreservesDisabledThinkingAndOutputCapAtAllTimeoutBoundaries() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"glm-4.6\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var gateway = new AdminChatModelGateway(null,new DefaultListableBeanFactory().getBeanProvider(ToolCallingManager.class)) {
                @Override protected ChatClient build(ModelSettings settings, boolean thinking) {
                    var local = new ModelSettings(true,"http://127.0.0.1:"+server.getAddress().getPort(),"fixture",settings.model(),settings.timeoutSeconds(),ModelSettings.Source.FILE,ModelSettings.KeyState.OK);
                    ChatModel model = ReflectionTestUtils.invokeMethod(this,"thinkingCapableModel",local,thinking);
                    return ChatClient.builder(model).build();
                }
            };
            gateway.apply(new ModelSettings(true,"https://open.bigmodel.cn/api/paas/v4","fixture","glm-4.6",30,ModelSettings.Source.FILE,ModelSettings.KeyState.OK));
            for(int timeout : new int[]{10,0,30,90}) {
                AgentBudgetAdvisor.client(gateway.chatClient(timeout),new AgentRunBudget(AgentRunLimits.defaults()),gateway::reasoningDelta)
                        .prompt().options(ChatOptions.builder().maxTokens(768)).user("fixture").call().content();
                var body=requests.get(requests.size()-1);
                assertEquals("disabled",body.path("thinking").path("type").asText());
                assertEquals(768,body.path("max_tokens").asInt());
            }
            gateway.chatClient().prompt().user("fixture").call().content();
            assertEquals("enabled",requests.get(requests.size()-1).path("thinking").path("type").asText());
        } finally { server.stop(0); }
    }
}
