package com.rover.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.rover.admin.web.AdminApiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制台访问控制：表单登录 + 会话 + 登出 + CSRF，覆盖全部页面与 {@code /api/*}。
 *
 * 未配置口令时不启用登录（保持项目"留空 = 仅本机调试"的既有约定），但仍打 WARN，
 * 且 {@code /api/auth/status} 会回 {@code authEnabled:false}，页面顶栏据此显示提示条 ——
 * 不安全状态必须可见，不能静默。
 *
 * CSRF 在两种模式下都启用：即使不登录，恶意页面也不该能借浏览器向本机管理口发写请求。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfiguration.class);

    private static final String LOGIN_PAGE = "/login.html";
    private static final String LOGIN_PROCESSING_URL = "/login";
    private static final String LOGOUT_URL = "/api/logout";

    /** 免登录：登录页、登录提交、登录态查询与静态资源。 */
    private static final String[] PUBLIC_PATHS = {
            LOGIN_PAGE, LOGIN_PROCESSING_URL, AdminApiPaths.PREFIX + AdminApiPaths.AUTH_STATUS,
            "/css/**", "/js/**", "/vendor/**", "/images/**", "/favicon.svg", "/favicon.ico", "/error"
    };

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AdminSecurityProperties properties,
                                                        LoginAttemptGuard loginAttemptGuard) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(PUBLIC_PATHS).permitAll();
            if (properties.isEnabled()) {
                auth.anyRequest().authenticated();
            } else {
                auth.anyRequest().permitAll();
            }
        });

        // 表单登录：登录页是静态页，失败统一跳 ?error=1，不区分"用户不存在/口令错"。
        http.formLogin(form -> form
                .loginPage(LOGIN_PAGE)
                .loginProcessingUrl(LOGIN_PROCESSING_URL)
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    loginAttemptGuard.reset(source(request, properties));
                    log.info("控制台登录成功：来源={} 用户={}", source(request, properties), authentication.getName());
                    response.sendRedirect(safeRedirect(request.getParameter("redirect")));
                })
                .failureHandler((request, response, exception) -> {
                    loginAttemptGuard.recordFailure(source(request, properties));
                    log.warn("控制台登录失败：来源={} 剩余尝试={}", source(request, properties),
                            loginAttemptGuard.remaining(source(request, properties)));
                    response.sendRedirect(LOGIN_PAGE + "?error=1");
                })
                .permitAll());

        // 登出只认 POST：GET 登出无法被 CSRF 保护，恶意页面可以借浏览器把操作者踢下线。
        http.logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_URL))
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK))
                .invalidateHttpSession(true)
                .deleteCookies("ROVERADMIN_SESSION", "XSRF-TOKEN"));

        http.sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.changeSessionId()));

        // API 请求回 401 JSON，页面请求跳登录页。
        http.exceptionHandling(handling -> handling.authenticationEntryPoint(apiAwareEntryPoint()));

        // 限流要挡在口令校验之前，否则爆破依然会消耗 BCrypt 计算。
        http.addFilterBefore(new LoginAttemptGuardFilter(loginAttemptGuard, properties),
                UsernamePasswordAuthenticationFilter.class);

        if (properties.isEnabled()) {
            log.info("控制台已启用登录鉴权：用户={} 口令来源={}", properties.getUsername(),
                    properties.isPlainTextPassword() ? "明文（建议改用 password-hash）" : "BCrypt 哈希");
            if (properties.isPlainTextPassword()) {
                log.warn("控制台使用的是明文口令配置，建议改为 rover.admin.auth.password-hash（BCrypt）");
            }
        } else {
            log.warn("未配置登录口令，控制台对所有来源开放且不鉴权，仅限本机调试；"
                    + "生产环境请设置 ROVER_ADMIN_PASSWORD_HASH");
        }
        return http.build();
    }

    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 用户来源。未启用登录时返回一个恒拒绝的实现，避免 Spring Boot 生成随机口令的默认用户，
     * 也避免日志里出现"Using generated security password"这种误导信息。
     */
    @Bean
    public UserDetailsService adminUserDetailsService(AdminSecurityProperties properties, PasswordEncoder encoder) {
        if (!properties.isEnabled()) {
            return username -> {
                throw new UsernameNotFoundException("控制台未启用登录鉴权");
            };
        }
        String hash = properties.isPlainTextPassword() ? encoder.encode(properties.getPassword())
                : properties.getPasswordHash();
        UserDetails user = User.withUsername(properties.getUsername())
                .password(hash)
                .roles("ADMIN")
                .build();
        return username -> {
            if (!properties.getUsername().equals(username)) {
                throw new UsernameNotFoundException("用户名或口令不正确");
            }
            return user;
        };
    }

    /** API 请求回 401 JSON（前端能直接提示），页面请求跳登录页。 */
    private static AuthenticationEntryPoint apiAwareEntryPoint() {
        return (request, response, exception) -> {
            if (request.getRequestURI().startsWith("/api/")) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录控制台");
            } else {
                response.sendRedirect(LOGIN_PAGE);
            }
        };
    }

    /** 只接受站内路径，避免登录后被跳到外部地址。 */
    private static String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank() || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/";
        }
        return redirect;
    }

    /**
     * 登录限流的来源地址：默认取 {@code remoteAddr}（直连对端，客户端无法伪造）。
     *
     * 只有显式打开 {@code rover.admin.auth.trust-forwarded-headers} 时才认
     * {@code X-Forwarded-For} 的首个地址——应用直接暴露时该头可以由任意客户端自带，
     * 无条件采信等于把"按来源限流"变成"按攻击者自选值限流"。
     */
    private static String source(HttpServletRequest request, AdminSecurityProperties properties) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }

    /** 登录限流前置过滤：只在 POST /login 上生效。 */
    private static final class LoginAttemptGuardFilter extends OncePerRequestFilter {

        private final LoginAttemptGuard guard;
        private final AdminSecurityProperties properties;

        private LoginAttemptGuardFilter(LoginAttemptGuard guard, AdminSecurityProperties properties) {
            this.guard = guard;
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
            boolean loginSubmit = "POST".equalsIgnoreCase(request.getMethod())
                    && LOGIN_PROCESSING_URL.equals(request.getRequestURI());
            if (loginSubmit && guard.isBlocked(source(request, properties))) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"code\":429,\"message\":\"登录失败次数过多，请稍后再试\"}");
                response.getWriter().flush();
                return;
            }
            chain.doFilter(request, response);
        }
    }
}