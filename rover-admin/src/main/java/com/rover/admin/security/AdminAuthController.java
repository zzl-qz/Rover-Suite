package com.rover.admin.security;

import com.rover.admin.web.AdminApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录页和前端启动时用它拿 CSRF 令牌，并判断当前是不是已经登录。
 */
@RestController
@RequestMapping(AdminApiPaths.PREFIX)
public class AdminAuthController {

    /** 当前登录态。匿名令牌不算已登录。 */
    @GetMapping(AdminApiPaths.AUTH_STATUS)
    public Map<String, Object> status(Authentication authentication, HttpServletRequest request, CsrfToken csrfToken) {
        boolean loggedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authEnabled", true);
        body.put("authenticated", loggedIn);
        body.put("username", loggedIn ? authentication.getName() : "");
        body.put("csrfToken", csrfToken == null ? "" : csrfToken.getToken());
        body.put("csrfHeaderName", csrfToken == null ? "X-XSRF-TOKEN" : csrfToken.getHeaderName());
        body.put("csrfParameterName", csrfToken == null ? "_csrf" : csrfToken.getParameterName());
        body.put("sessionTimeoutSeconds", sessionTimeout(request));
        return body;
    }

    private static int sessionTimeout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? -1 : session.getMaxInactiveInterval();
    }
}
