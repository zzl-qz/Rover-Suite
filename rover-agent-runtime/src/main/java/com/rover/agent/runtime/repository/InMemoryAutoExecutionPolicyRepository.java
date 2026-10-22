package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.AutoExecutionPolicy;
import com.rover.agent.core.repository.AutoExecutionPolicyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded single-process policy repository for explicit tests and memory mode. */
public final class InMemoryAutoExecutionPolicyRepository implements AutoExecutionPolicyRepository {
    private final Map<String, AutoExecutionPolicy> current = new LinkedHashMap<>();
    private final Map<String, List<AutoExecutionPolicy>> history = new LinkedHashMap<>();

    @Override public synchronized List<AutoExecutionPolicy> list(String namespace) {
        return current.values().stream().filter(policy -> policy.targetNamespace().equals(namespace))
                .sorted(Comparator.comparing(AutoExecutionPolicy::policyId)).toList();
    }
    @Override public synchronized Optional<AutoExecutionPolicy> find(String id) { return Optional.ofNullable(current.get(id)); }
    @Override public synchronized Optional<AutoExecutionPolicy> find(String id, String namespace) {
        return find(id).filter(policy -> policy.targetNamespace().equals(namespace));
    }
    @Override public synchronized List<AutoExecutionPolicy> history(String id) { return List.copyOf(history.getOrDefault(id, List.of())); }
    @Override public synchronized AutoExecutionPolicy save(AutoExecutionPolicy policy, long expectedVersion) {
        AutoExecutionPolicy before = current.get(policy.policyId());
        long actual = before == null ? 0 : before.version();
        if (actual != expectedVersion || policy.version() != expectedVersion + 1
                || before != null && !before.targetNamespace().equals(policy.targetNamespace())) {
            throw new IllegalStateException("preauthorization policy version conflict");
        }
        current.put(policy.policyId(), policy);
        history.computeIfAbsent(policy.policyId(), ignored -> new ArrayList<>()).add(policy);
        return policy;
    }
}
