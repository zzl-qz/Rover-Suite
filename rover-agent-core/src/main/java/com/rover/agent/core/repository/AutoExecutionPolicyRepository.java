package com.rover.agent.core.repository;

import com.rover.agent.core.model.AutoExecutionPolicy;
import java.util.List;
import java.util.Optional;

/** Durable versioned preauthorization records. Saves are compare-and-set, not last-write-wins. */
public interface AutoExecutionPolicyRepository {
    List<AutoExecutionPolicy> list(String targetNamespace);
    Optional<AutoExecutionPolicy> find(String policyId);
    Optional<AutoExecutionPolicy> find(String policyId, String targetNamespace);
    AutoExecutionPolicy save(AutoExecutionPolicy policy, long expectedVersion);
    List<AutoExecutionPolicy> history(String policyId);
}
