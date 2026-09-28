package com.rover.admin.security;

import com.rover.admin.web.AdminApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * 控制台必须先登录。账号在 H2 的 {@code admin_user} 表里，由 {@link AdminUserStore} 提供。
 *
 * 页面没登录就跳到登录页，接口没登录回 401。CSRF 仍然开着，避免别的网页借浏览器改路由。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AdminSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfiguration.class);

    private static final String LOGIN_PAGE = "/login.html";
    private static final String LOGIN_PROCESSING_URL = "/login";
    private static final String LOGOUT_URL = "/api/logout";

    /** 登录页、登录提交、登录态查询和静态资源不要求已登录。 */
    private static final String[] PUBLIC_PATHS = {
            LOGIN_PAGE, LOGIN_PROCESSING_URL, AdminApiPaths.PREFIX + AdminApiPaths.AUTH_STATUS,
            "/css/**", "/js/**", "/vendor/**", "/images/**", "/favicon.svg", "/favicon.ico", "/error"
    };

    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated());

        http.formLogin(form -> form
                .loginPage(LOGIN_PAGE)
                .loginProcessingUrl(LOGIN_PROCESSING_URL)
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    log.info("控制台登录成功：用户={}", authentication.getName());
                    response.sendRedirect(safeRedirect(request.getParameter("redirect")));
                })
                .failureHandler((request, response, exception) ->
                        response.sendRedirect(LOGIN_PAGE + "?error=1"))
                .permitAll());

        http.logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_URL))
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_OK))
                .invalidateHttpSession(true)
                .deleteCookies("ROVERADMIN_SESSION", "XSRF-TOKEN"));

        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        http.exceptionHandling(handling -> handling.authenticationEntryPoint(apiAwareEntryPoint()));
        log.info("控制台登录已启用，未登录不能进入页面和接口");
        return http.build();
    }

    /** 接口回 401，页面跳到登录页。 */
    private static AuthenticationEntryPoint apiAwareEntryPoint() {
        return (request, response, exception) -> {
            if (request.getRequestURI().startsWith("/api/")) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录控制台");
            } else {
                response.sendRedirect(LOGIN_PAGE);
            }
        };
    }

    /** 只接受站内路径，避免登录后被带去外部地址。 */
    private static String safeRedirect(String redirect) {
        if (redirect == null || redirect.isBlank() || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/";
        }
        return redirect;
    }

    private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }
}
