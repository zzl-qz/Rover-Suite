package com.rover.agent.runtime.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.model.Session;
import com.rover.agent.runtime.task.IncidentRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedSessionPersistenceTest {
    @TempDir Path directory;
    @Test void scopesSurviveRestartAndSameNamesCannotCrossEnvironmentOrTarget() throws Exception {
        String path = directory.resolve("scoped").toString();
        ResourceScope scope = new ResourceScope("org", "ops", "prod");
        Session session;
        try (AgentStore store = AgentStore.file(path)) {
            session = new IncidentRegistry(store.sessions(), store.incidents(), null, scope, "gateway-one").openSession("alice");
        }
        try (AgentStore store = AgentStore.file(path)) {
            Session saved = store.sessions().find(session.sessionId()).orElseThrow();
            assertEquals(scope, saved.scope());
            assertEquals("gateway-one", saved.targetNamespace());
            assertTrue(new IncidentRegistry(store.sessions(), store.incidents(), null, scope, "gateway-one").accepts(saved));
            assertFalse(new IncidentRegistry(store.sessions(), store.incidents(), null, scope, "gateway-two").accepts(saved));
            assertFalse(new IncidentRegistry(store.sessions(), store.incidents(), null,
                    new ResourceScope("org", "ops", "staging"), "gateway-one").accepts(saved));
        }
    }
}
