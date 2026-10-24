package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.model.*;
import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.repository.JdbcAgentStore;
import com.rover.agent.runtime.task.*;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ModelCallLedgerTest {
    @TempDir Path root;
    static AgentRunBudget budget() { return new AgentRunBudget(new AgentRunLimits(8,64,20000,Duration.ofSeconds(30),3)); }
    static final ModelIdentity IDENTITY=new ModelIdentity("FIXTURE","requested-model","endpoint-digest","config-rev",15,false);
    static ModelClient client(Function<Prompt,Flux<ChatResponse>> script,ProviderIsolation isolation) {
        var client=ChatClient.create(new ChatModel() {
            public ChatResponse call(Prompt prompt) { return script.apply(prompt).blockLast(); }
            public Flux<ChatResponse> stream(Prompt prompt) { return script.apply(prompt); }
        });
        return new ModelClient(client,IDENTITY,isolation,"provider-key");
    }
    static ChatClient guarded(ModelClient client,AgentRunBudget budget) { return AgentBudgetAdvisor.client(client,budget,r->"","planning",ModelContracts.PLANNING); }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),ChatResponseMetadata.builder().model("resolved-model-v2").build()); }

    @Test void recordsFrozenClientIdentityAndReturnedModelWithoutPromptContent() throws Exception {
        var budget=budget();var snapshots=new ArrayList<AgentRunBudget.State>();budget.persistTo(snapshots::add);
        var client=client(p->Flux.just(answer("ok")),new ProviderIsolation(ProviderIsolation.Policy.defaults()));
        guarded(client,budget).prompt().system("private system prompt").user("private business context").call().content();
        var call=budget.modelInvocations().get(0);assertEquals("SUCCEEDED",call.status());assertEquals("NOT_EVALUATED",call.validation());
        assertEquals(IDENTITY,call.identity());assertEquals("resolved-model-v2",call.returnedModel());assertEquals(64,call.promptDigest().length());
        assertNotNull(call.completedAtMillis());assertNull(call.systemFingerprint());
        String json=new ObjectMapper().writeValueAsString(budget.snapshot());assertFalse(json.contains("private system"));assertFalse(json.contains("private business"));
        assertEquals(0,budget.snapshot().pendingOutput());
        assertTrue(snapshots.stream().anyMatch(s->!s.modelInvocations().isEmpty() && s.pendingOutput()>0));
        assertEquals("SUCCEEDED",snapshots.get(snapshots.size()-1).modelInvocations().get(0).status());
    }

    @Test void partialUsageInputCorrectionCannotConsumeUnseenOutputReservation() {
        var budget=budget();
        var partial=new ChatResponse(List.of(new Generation(new AssistantMessage("x"))),ChatResponseMetadata.builder().usage(new DefaultUsage(500,5)).build());
        var client=client(p->Flux.concat(Flux.just(partial),Flux.error(new RuntimeException(new ConnectException()))),null);
        assertThrows(RuntimeException.class,()->guarded(client,budget).prompt().user("x").stream().chatResponse().blockLast());
        assertEquals(564,budget.snapshot().tokens());assertEquals(0,budget.snapshot().pendingOutput());
        var call=budget.modelInvocations().get(0);assertEquals(500,call.accountedInputTokens());assertEquals(64,call.accountedOutputTokens());assertEquals("UNAVAILABLE",call.status());
    }

    @Test void openCircuitIsRecordedWithoutSendingOrChargingAnotherModelRequest() {
        AtomicInteger sent=new AtomicInteger();var isolation=new ProviderIsolation(new ProviderIsolation.Policy(1,30,1));
        var client=client(p->{sent.incrementAndGet();return Flux.error(new RuntimeException(new ConnectException()));},isolation);
        assertThrows(RuntimeException.class,()->guarded(client,budget()).prompt().user("x").call().content());
        var next=budget();assertThrows(RuntimeException.class,()->guarded(client,next).prompt().user("x").call().content());
        assertEquals(1,sent.get());assertEquals(0,next.snapshot().modelCalls());assertEquals(0,next.snapshot().tokens());assertEquals("CIRCUIT_OPEN",next.modelInvocations().get(0).status());
    }

    @Test void validTransportAndRejectedApplicationOutputAreDifferentOutcomes() {
        var isolation=new ProviderIsolation(new ProviderIsolation.Policy(1,30,1));var model=client(p->Flux.just(answer("unusable JSON")),isolation);
        var registry=new InvestigationTaskRegistry();var task=registry.register("s","test");task.start();task.markRunning();
        try {
            ChatModelGateway gateway=new Gateway(model);
            var completion=SpringAiJsonCompletion.investigation(gateway,AgentMetrics.NOOP,15,64,false);
            assertTrue(completion.complete("system","question",raw->Optional.empty()).isEmpty());
            var invocation=task.budget().modelInvocations().get(0);assertEquals("SUCCEEDED",invocation.status());assertEquals("REJECTED",invocation.validation());
            assertTrue(completion.complete("system","question",Optional::of).isPresent());
            assertEquals("ACCEPTED",task.budget().modelInvocations().get(1).validation());
        } finally { task.clearRunning();registry.stop(); }
    }

    @Test void interruptedLedgerAndBudgetSurviveH2RestartWithoutReset() throws Exception {
        String path=root.resolve("ledger").toString();AgentRunBudget.State saved;
        try(var store=new JdbcAgentStore(path)) {
            store.sessions().save(new Session("s","alice","test",null,SessionStatus.ACTIVE,1,1,List.of()));
            store.tasks().save(new TaskView("t","s",null,TaskStatus.RUNNING,null,"/p",ResourceTarget.route("/p"),"why",1,0,List.of(),null,null,null));
            store.enqueueJob("t","TEST",Map.of());var job=store.claimJob("t",0).orElseThrow();var budget=budget();budget.persistTo(s->store.saveBudget("t",job.attempt(),s));
            int output=budget.beginModelCall(10,64);budget.invocationStarted("planning",IDENTITY,ModelContracts.PLANNING,"hash","tools",10,output);budget.consume(5);saved=budget.snapshot();
        }
        try(var store=new JdbcAgentStore(path)) {
            var job=store.claimJob("t",1).orElseThrow();var state=new ObjectMapper().readValue(job.budgetJson(),AgentRunBudget.State.class);
            var restored=budget();restored.restore(state);restored.persistTo(s->store.saveBudget("t",job.attempt(),s));restored.modelCallCompleted();
            assertEquals(74,restored.snapshot().tokens());assertEquals(1,restored.snapshot().modelCalls());assertEquals(saved.deadlineAtMillis(),restored.snapshot().deadlineAtMillis());
            assertEquals("INTERRUPTED",store.tasks().find("t").orElseThrow().modelInvocations().get(0).status());
            var again=budget();again.restore(restored.snapshot());assertEquals(74,again.snapshot().tokens());
        }
    }

    @Test void legacyBudgetJsonStillLoadsAndHasNoFabricatedModelHistory() throws Exception {
        String old="{\"modelCalls\":1,\"tokens\":10,\"pendingOutput\":0,\"toolCalls\":2,\"deadlineAtMillis\":0,\"maxModelCalls\":8,\"maxOutputTokens\":64,\"maxTotalTokens\":20000,\"timeoutMillis\":30000,\"maxRepeatedToolResults\":3}";
        var state=new ObjectMapper().readValue(old,AgentRunBudget.State.class);assertTrue(state.modelInvocations().isEmpty());var budget=budget();budget.restore(state);assertEquals(1,budget.snapshot().modelCalls());
    }

    private record Gateway(ModelClient selected) implements ChatModelGateway {
        public boolean configured(){return true;}public boolean available(){return true;}
        public ChatClient chatClient(){return selected.client();}public ChatClient chatClient(int timeout){return selected.client();}
        public ChatClient planningClient(int timeout,boolean thinking){return selected.client();}
        public ModelClient select(Purpose purpose,int timeout,boolean thinking){return selected;}
        public String description(){return "fixture";}public String reasoningDelta(ChatResponse response){return "";}
    }
}
