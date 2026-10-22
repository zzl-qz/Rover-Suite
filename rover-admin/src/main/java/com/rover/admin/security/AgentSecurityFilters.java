package com.rover.admin.security;

import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.AgentAccess.Permission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AgentSecurityFilters {
    static final String MACHINE = AgentSecurityFilters.class.getName() + ".machine";
    static final String INGEST = "/api/agent/events/ingest";
    static final String RELEASE_INGEST = "/api/agent/releases/ingest";
    private AgentSecurityFilters() { }
    static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return servletPath.isEmpty() ? request.getRequestURI().substring(request.getContextPath().length()) : servletPath;
    }

    static final class Machine extends OncePerRequestFilter {
        private final AgentSecurityService security;
        Machine(AgentSecurityService security) { this.security = security; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) { chain.doFilter(request, response); return; }
            if (!(INGEST.equals(path(request)) || RELEASE_INGEST.equals(path(request))) || !"POST".equals(request.getMethod())) {
                error(response, 403, "机器令牌仅可向对应事件入口提交"); return;
            }
            AgentSecurityService.Machine machine;
            try { machine = security.authenticate(header.substring(7)); }
            catch (AgentAccess.Denied denied) { error(response, 403, "机器来源没有有效处理账号"); return; }
            if (machine == null) { error(response, 401, "机器令牌无效、过期或已撤销"); return; }
            String allowedPath = "RELEASE".equals(machine.purpose()) ? RELEASE_INGEST : INGEST;
            if (!allowedPath.equals(path(request))) { error(response, 403, "机器令牌用途与事件入口不匹配"); return; }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(machine.principal(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_CONNECTOR"))));
            SecurityContextHolder.setContext(context);
            request.setAttribute(MACHINE, machine);
            try (var audit = com.rover.agent.core.util.AuditActor.enter(machine.principal())) {
                chain.doFilter(request, response);
            }
        }
    }

    static final class Permissions extends OncePerRequestFilter {
        private final AgentSecurityService security;
        Permissions(AgentSecurityService security) { this.security = security; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication actor = SecurityContextHolder.getContext().getAuthentication();
            String path = path(request);
            if (actor == null || !actor.isAuthenticated() || actor instanceof AnonymousAuthenticationToken
                    || !path.startsWith("/api/") || path.equals("/api/auth/status") || path.equals("/api/logout")) {
                chain.doFilter(request, response); return;
            }
            if (request.getAttribute(MACHINE) != null) { chain.doFilter(request, response); return; }
            try { security.require(actor.getName(), permission(path, request.getMethod())); }
            catch (AgentAccess.Denied denied) { error(response, 403, "当前账号没有此操作权限，或已被撤权"); return; }
            if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                    && !path.startsWith("/api/agent/") && security.writesStopped()) {
                error(response, 403, "生产写入已暂停"); return;
            }
            try (var audit = com.rover.agent.core.util.AuditActor.enter(actor.getName())) {
                chain.doFilter(request, response);
            }
        }
    }

    /** Release payloads have a fixed small contract; also cap chunked requests before JSON deserialization. */
    static final class ReleasePayload extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!RELEASE_INGEST.equals(path(request)) || !"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
            if (request.getContentLengthLong() > 16_384) { error(response, 413, "发布事件不得超过 16KiB"); return; }
            byte[] bytes = request.getInputStream().readNBytes(16_385);
            if (bytes.length > 16_384) { error(response, 413, "发布事件不得超过 16KiB"); return; }
            var body = new java.io.ByteArrayInputStream(bytes);
            var wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override public jakarta.servlet.ServletInputStream getInputStream() {
                    return new jakarta.servlet.ServletInputStream() {
                        public int read() { return body.read(); }
                        public int read(byte[] target, int offset, int length) { return body.read(target, offset, length); }
                        public boolean isFinished() { return body.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(jakarta.servlet.ReadListener listener) { throw new IllegalStateException("发布入口使用同步请求体读取"); }
                    };
                }
            };
            chain.doFilter(wrapped, response);
        }
    }

    static Permission permission(String path, String method) {
        boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (path.equals("/api/agent/security/me")) { return Permission.READ; }
        if (path.equals("/api/agent/actions/reconciliation")) { return Permission.MANAGE_SYSTEM; }
        if (path.startsWith("/api/agent/operations")) { return Permission.MANAGE_SYSTEM; }
        if (path.equals("/api/agent/security/users")) { return Permission.MANAGE_SYSTEM; }
        if (path.startsWith("/api/agent/security/") || path.startsWith("/api/agent/model")) { return Permission.POLICY_ADMIN; }
        if (path.startsWith("/api/agent/policies/autonomous")) { return Permission.POLICY_ADMIN; }
        if (path.startsWith("/api/agent/actions/sessions/") && path.endsWith("/approval-progress")) { return Permission.READ; }
        if (path.startsWith("/api/agent/actions/") && path.endsWith("/approval-progress")) { return Permission.APPROVE; }
        if (path.startsWith("/api/agent/actions/") && path.endsWith("/reconcile")) { return Permission.MANAGE_SYSTEM; }
        if (read) { return Permission.READ; }
        if (path.startsWith("/api/agent/actions/")) {
            return path.endsWith("/resolve") ? Permission.READ : Permission.APPROVE;
        }
        if (path.startsWith("/api/agent/v1/plans/") && path.endsWith("/versions")) { return Permission.PROPOSE; }
        if (path.equals(INGEST) || path.equals(RELEASE_INGEST)) { return Permission.INGEST; }
        return path.startsWith("/api/agent/") ? Permission.INVESTIGATE : Permission.MANAGE_SYSTEM;
    }

    private static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
