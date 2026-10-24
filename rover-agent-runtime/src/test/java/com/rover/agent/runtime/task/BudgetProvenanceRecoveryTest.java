package com.rover.agent.runtime.task;

import com.rover.agent.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BudgetProvenanceRecoveryTest {
    @Test void oldBudgetRestorationRetainsTemplateVersionsFromAlreadyPersistedEvidence() {
        var registry=new InvestigationTaskRegistry();
        try {
            var task=registry.register("s","test");
            var evidence=Evidence.of(task.taskId(),EvidenceType.LOG,"logs","logs","redacted logs","source",1);
            task.recordEvidence(List.of(evidence));
            var state=new AgentRunBudget.State(1,10,0,1,0,8,64,10000,30000,3);
            task.restoreBudget(state);
            var snapshots=new ArrayList<AgentRunBudget.State>();task.budget().persistTo(snapshots::add);
            task.budget().beginModelInvocation("planning",ModelIdentity.unknown(),"planning-v3","prompt","tools",10,64);
            assertEquals(List.of("legacy-v1"),task.budget().modelInvocations().get(0).queryTemplateVersions());
            assertEquals(2,task.budget().snapshot().modelCalls());
            assertTrue(snapshots.stream().allMatch(s->s.modelCalls()!=2 || s.modelInvocations().size()==1),"调用预算与身份记录必须一次落盘");
        } finally {registry.stop();}
    }
}
