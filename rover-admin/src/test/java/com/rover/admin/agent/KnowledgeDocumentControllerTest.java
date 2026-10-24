package com.rover.admin.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.AgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class KnowledgeDocumentControllerTest {
    @TempDir Path temp;
    @Test void persistentKnowledgeRequiresPolicyAdminAndKeepsReviewAndScopeBoundariesAcrossRestart() throws Exception {
        var scope = new ResourceScope("org", "team", "dev");
        RouteControlPort control = mock(RouteControlPort.class); when(control.resourceScope()).thenReturn(scope);
        AgentSecurityService security = mock(AgentSecurityService.class);
        AgentStore store = AgentStore.file(temp.resolve("agent-store").toString());
        var controller = new KnowledgeDocumentController(store,control,security);
        var viewer = new UsernamePasswordAuthenticationToken("viewer","n/a");
        doThrow(new com.rover.agent.core.port.AgentAccess.Denied("policy admin required"))
                .when(security).require("viewer", Permission.POLICY_ADMIN);
        assertThrows(com.rover.agent.core.port.AgentAccess.Denied.class, () -> controller.list(viewer));
        var author = new UsernamePasswordAuthenticationToken("author","n/a");
        var draft = controller.create(java.util.Map.of("title","Runbook", "content","route evidence", "sourceVersion","git:1"), author);
        assertEquals(201, ((org.springframework.http.ResponseEntity<?>) draft).getStatusCode().value());
        var body = ((org.springframework.http.ResponseEntity<?>) draft).getBody();
        var document = (KnowledgeDocumentVersion) body;
        assertEquals(403, ((org.springframework.http.ResponseEntity<?>) controller.approve(document.documentId(),document.version(),author))
                .getStatusCode().value());
        var approved = controller.approve(document.documentId(),document.version(),
                new UsernamePasswordAuthenticationToken("reviewer","n/a"));
        assertTrue(approved instanceof org.springframework.http.ResponseEntity<?> response && response.getStatusCode().is2xxSuccessful());
        var next = controller.createNextVersion(document.documentId(),
                java.util.Map.of("title","Runbook v2","content","updated route evidence","sourceVersion","git:2"), author);
        assertEquals(201, next.getStatusCode().value());
        assertEquals(2, ((KnowledgeDocumentVersion) next.getBody()).version());
        assertEquals(409, controller.createNextVersion(document.documentId(),
                java.util.Map.of("title","duplicate draft","content","must not replace draft","sourceVersion","git:2b"), author).getStatusCode().value());
        assertEquals(403, ((org.springframework.http.ResponseEntity<?>) controller.approve(document.documentId(), 2, author)).getStatusCode().value());
        assertTrue(controller.approve(document.documentId(), 2,
                new UsernamePasswordAuthenticationToken("reviewer","n/a")) instanceof org.springframework.http.ResponseEntity<?> response
                && response.getStatusCode().is2xxSuccessful());
        store.knowledgeDocuments().createDraft("other-scope", new ResourceScope("org","team","prod"),
                "secret","production only", java.util.List.of(),"git:secret","writer",System.currentTimeMillis()+60_000);
        assertThrows(IllegalStateException.class, () -> store.knowledgeDocuments().createDraft(document.documentId(),
                new ResourceScope("org","team","prod"), "cross scope","forbidden",java.util.List.of(),
                "git:bad","writer",System.currentTimeMillis()+60_000));
        assertEquals(2, ((java.util.List<?>) controller.list(author)).size());
        var revoked = controller.revoke(document.documentId(),document.version(),
                new UsernamePasswordAuthenticationToken("reviewer","n/a"));
        assertTrue(revoked instanceof org.springframework.http.ResponseEntity<?> response && response.getStatusCode().is2xxSuccessful());
        assertEquals(404, ((org.springframework.http.ResponseEntity<?>) controller.revoke("other-scope",1,
                new UsernamePasswordAuthenticationToken("reviewer","n/a"))).getStatusCode().value());
        var nextV3 = controller.createNextVersion(document.documentId(),
                java.util.Map.of("title","Runbook v3","content","new route evidence","sourceVersion","git:3"),
                new UsernamePasswordAuthenticationToken("author","n/a"));
        assertEquals(201, nextV3.getStatusCode().value());
        assertEquals(3, ((KnowledgeDocumentVersion) nextV3.getBody()).version());
        verify(security, atLeast(1)).require("author", Permission.POLICY_ADMIN);
        verify(security, atLeast(1)).require("reviewer", Permission.POLICY_ADMIN);
        store.close();

        try (AgentStore reopened = AgentStore.file(temp.resolve("agent-store").toString())) {
            var restored = new KnowledgeDocumentController(reopened,control,security).list(
                    new UsernamePasswordAuthenticationToken("reader","n/a"));
            assertEquals(3, ((java.util.List<?>) restored).size());
            assertEquals(KnowledgeDocumentVersion.Status.REVOKED, reopened.knowledgeDocuments()
                    .find(document.documentId(),1).orElseThrow().status());
            assertEquals(KnowledgeDocumentVersion.Status.APPROVED, reopened.knowledgeDocuments()
                    .find(document.documentId(),2).orElseThrow().status());
        }
    }
}
