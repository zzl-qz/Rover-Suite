package com.rover.admin.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rover.agent.core.port.AgentAccess.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;

class AdminAuthControllerTest {
    @Test
    void exposesAuthenticatedAccountStateToTheAdminPage() {
        AgentSecurityService security = mock(AgentSecurityService.class);
        when(security.permissions("admin")).thenReturn(Set.of(Permission.READ, Permission.MANAGE_SYSTEM));
        when(security.writesStopped()).thenReturn(true);
        AdminAuthController controller = new AdminAuthController(security);

        var body = controller.status(UsernamePasswordAuthenticationToken.authenticated("admin", null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))),
                mock(HttpServletRequest.class), mock(CsrfToken.class));

        assertEquals(true, body.get("authenticated"));
        assertEquals(java.util.List.of("MANAGE_SYSTEM", "READ"), body.get("permissions"));
        assertEquals(true, body.get("writesStopped"));
        verify(security).permissions("admin");
        verify(security).writesStopped();
    }

    @Test
    void anonymousAccountReceivesNoPermissions() {
        AgentSecurityService security = mock(AgentSecurityService.class);
        AdminAuthController controller = new AdminAuthController(security);

        var body = controller.status(null, mock(HttpServletRequest.class), mock(CsrfToken.class));

        assertEquals(false, body.get("authenticated"));
        assertEquals(java.util.List.of(), body.get("permissions"));
        verify(security).writesStopped();
    }
}
