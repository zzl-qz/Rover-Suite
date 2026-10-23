package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.ActionCertification;
import com.rover.agent.core.model.ActionCertificationSubject;
import com.rover.agent.core.model.ActionShadowRun;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.repository.ActionCertificationRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded to the current in-memory test/development lifetime; production still requires durable H2. */
public final class InMemoryActionCertificationRepository implements ActionCertificationRepository {
    private final Map<String, ActionShadowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, List<ActionCertification>> certifications = new ConcurrentHashMap<>();

    @Override public ActionShadowRun saveShadow(ActionShadowRun run) {
        if (runs.putIfAbsent(run.runId(), run) != null) { throw new IllegalStateException("duplicate shadow run"); }
        return run;
    }
    @Override public List<ActionShadowRun> recentShadowRuns(String fingerprint, int limit) {
        validateLimit(limit);
        return runs.values().stream().filter(run -> run.subject().fingerprint().equals(fingerprint))
                .sorted(Comparator.comparingLong(ActionShadowRun::observedAtMillis).reversed())
                .limit(limit).toList();
    }
    @Override public List<ActionShadowRun> recentShadowRuns(ResourceScope scope, int limit) {
        validateLimit(limit);
        return runs.values().stream().filter(run -> run.subject().scope().equals(scope))
                .sorted(Comparator.comparingLong(ActionShadowRun::observedAtMillis).reversed())
                .limit(limit).toList();
    }
    @Override public Optional<ActionCertification> current(String fingerprint) {
        return certifications.getOrDefault(fingerprint, List.of()).stream()
                .max(Comparator.comparingLong(ActionCertification::version));
    }
    @Override public Optional<ActionCertification> atVersion(String fingerprint, long version) {
        return certifications.getOrDefault(fingerprint, List.of()).stream()
                .filter(cert -> cert.version() == version).findFirst();
    }
    @Override public List<ActionCertification> current(ResourceScope scope) {
        return certifications.values().stream().map(history -> history.get(history.size() - 1))
                .filter(cert -> cert.subject().scope().equals(scope))
                .sorted(Comparator.comparing(cert -> cert.subject().actionType().name())).toList();
    }
    @Override public synchronized ActionCertification save(ActionCertification cert, long expectedVersion) {
        String fingerprint = cert.subject().fingerprint();
        List<ActionCertification> history = new ArrayList<>(certifications.getOrDefault(fingerprint, List.of()));
        long current = history.isEmpty() ? 0 : history.get(history.size() - 1).version();
        if (current != expectedVersion || cert.version() != current + 1) { throw new IllegalStateException("certification version conflict"); }
        history.add(cert); certifications.put(fingerprint, List.copyOf(history)); return cert;
    }
    private static void validateLimit(int limit) { if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100"); }
}
