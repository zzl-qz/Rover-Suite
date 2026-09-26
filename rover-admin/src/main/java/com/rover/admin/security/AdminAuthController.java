package com.rover.admin.security;

import com.rover.admin.web.AdminApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录态查询：前端启动时先问一次，据此决定是否显示登录提示与是否附带 CSRF 头。
 *
 * 顺带回传 CSRF 令牌：SPA 的写请求走 {@code X-XSRF-TOKEN} 头，必须先把 cookie 铺上。
 * 取一次 {@link CsrfToken} 参数就会触发 {@code CsrfFilter} 落盘 cookie（见类注释）。
 *
 * 登出不在这里：{@code POST /api/logout} 由 Spring Security 的 LogoutFilter 直接处理，
 * 早于 MVC，写到控制器里也是死代码。
 */
@RestController
@RequestMapping(AdminApiPaths.PREFIX)
public class AdminAuthController {

    private final AdminSecurityProperties properties;

    public AdminAuthController(AdminSecurityProperties properties) {
        this.properties = properties;
    }

    /** 当前鉴权配置与登录态；未启用登录时也要正常返回，页面据此显示提示条。 */
    @GetMapping(AdminApiPaths.AUTH_STATUS)
    public Map<String, Object> status(Authentication authentication, HttpServletRequest request,
                                      CsrfToken csrfToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authEnabled", properties.isEnabled());
        body.put("authenticated", authentication != null && authentication.isAuthenticated());
        body.put("username", authentication == null ? "" : authentication.getName());
        body.put("csrfToken", csrfToken == null ? "" : csrfToken.getToken());
        body.put("csrfHeaderName", csrfToken == null ? "X-XSRF-TOKEN" : csrfToken.getHeaderName());
        body.put("csrfParameterName", csrfToken == null ? "_csrf" : csrfToken.getParameterName());
        body.put("sessionTimeoutSeconds", sessionTimeout(request));
        return body;
    }

    /** 会话真实超时，唯一真值来自 {@code server.servlet.session.timeout}。 */
    private static int sessionTimeout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? -1 : session.getMaxInactiveInterval();
    }
}