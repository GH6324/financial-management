package com.family.finance.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import javax.sql.DataSource;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final MemberPrincipalService memberPrincipalService;

    @Value("${app.remember-me-key}")
    private String rememberMeKey;

    @Value("${app.remember-me-validity-seconds:2592000}")
    private int rememberMeValiditySeconds;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // TDD § 6.1 规定 12;BCrypt 验证时从 hash 自带的 salt 读 cost,旧的 cost-10 hash 仍兼容
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(memberPrincipalService);
        p.setPasswordEncoder(passwordEncoder());
        return p;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public PersistentTokenRepository tokenRepository(DataSource ds) {
        JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
        repo.setDataSource(ds);
        // 表 persistent_logins 已在 V1__init.sql 创建,这里不再 createTableOnStartup
        return repo;
    }

    @Bean
    public RememberMeServices rememberMeServices(PersistentTokenRepository tokenRepository) {
        PersistentTokenBasedRememberMeServices svc = new PersistentTokenBasedRememberMeServices(
                rememberMeKey, memberPrincipalService, tokenRepository);
        svc.setTokenValiditySeconds(rememberMeValiditySeconds);
        svc.setParameter("remember-me");
        svc.setUseSecureCookie(false); // TLS 在用户更上游处终结
        return svc;
    }

    @Bean
    public AuthenticationSuccessHandler successHandler() {
        SimpleUrlAuthenticationSuccessHandler h = new SimpleUrlAuthenticationSuccessHandler("/");
        h.setAlwaysUseDefaultTargetUrl(false);
        return h;
    }

    /**
     * CSRF / 权限被拒时的落点。
     *
     * <p>CSRF 失败在 Spring Security 里走 {@code AccessDeniedHandler}
     * ({@code InvalidCsrfTokenException} / {@code MissingCsrfTokenException})。默认行为是 403 +
     * 错误页,而这类失败**几乎总是"页面放太久了"**,不是攻击 —— 甩错误页只会让用户懵。</p>
     *
     * <p>处置:CSRF 类失败一律 302 回 {@code /login?stale};其余真正的权限不足仍返回 403
     * (那才是"你不该访问这里",错误页是对的)。POST 回登录页而不是原路重放 ——
     * 表单里的密码不会被带走,让用户自己重填一次最干净。</p>
     */
    @Bean
    public org.springframework.security.web.access.AccessDeniedHandler staleFormAccessDeniedHandler() {
        var fallback = new org.springframework.security.web.access.AccessDeniedHandlerImpl();
        return (request, response, ex) -> {
            if (ex instanceof org.springframework.security.web.csrf.CsrfException) {
                String ctx = request.getContextPath() == null ? "" : request.getContextPath();
                response.sendRedirect(ctx + "/login?stale");
                return;
            }
            fallback.handle(request, response, ex);
        };
    }

    @Bean
    public AuthenticationFailureHandler failureHandler() {
        return new SimpleUrlAuthenticationFailureHandler("/login?error");
    }

    /**
     * v1.15 FR-381 · 会话登记簿 —— 归档 / 改登录名要「当场生效」,就得能找到那个人**已经开着的**会话。
     *
     * <p>没有这张登记簿的话,归档只在下次登录时才拦得住:被归档的人手上那个标签页还能继续用,
     * 直到 session 自然过期(可能是几小时后)。而这个功能的用户预期恰恰是「点完立刻掉线」。
     *
     * <p>{@link org.springframework.security.web.session.HttpSessionEventPublisher} 是配套的:
     * 没有它,session 销毁事件不会回到 registry,登记簿会一直堆着早就死掉的会话。
     */
    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }

    @Bean
    public org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RememberMeServices rememberMeServices) throws Exception {
        http
            // v1.15 FR-381 · 把会话登进登记簿。不设 maximumSessions —— 一家人常常
            // 手机 + 电脑同时开着,限并发数会误伤;这里要的只是「找得到、踢得掉」。
            // expiredUrl 决定被踢的人下一次点击落在哪:回登录页并说清原因,别甩 403。
            .sessionManagement(s -> s
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry())
                .expiredUrl("/login?expired")
            )
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/login", "/login/error", "/static/**", "/css/**", "/vendor/**", "/img/**", "/uploads/**", "/js/**", "/manifest.webmanifest", "/favicon.ico", "/error", "/help/how-to-use", "/health", "/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler(successHandler())
                .failureHandler(failureHandler())
                .permitAll()
            )
            .rememberMe(rm -> rm
                .rememberMeServices(rememberMeServices)
                .key(rememberMeKey)
            )
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .deleteCookies("JSESSIONID", "remember-me")
                .permitAll()
            )
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())  // 非 XOR · 表单 _csrf 与 cookie 一致
            )
            // CSRF token 失效不该甩一个「印泥洒了」错误页。这个场景太日常了:
            // 登录页开着放久了 / 服务重启过 / 点了后退再提交 / 在另一个标签页登录登出过 ——
            // 用户看到的是「登录后 URL 还停在 /login + 报错页」,完全不知道该做什么。
            // 一律回登录页并说清「页面停留太久」,让他重填一次就好。
            .exceptionHandling(e -> e.accessDeniedHandler(staleFormAccessDeniedHandler()))
            .headers(h -> h
                .frameOptions(f -> f.disable())
                .cacheControl(c -> c.disable())  // 改由 WebMvcConfig+CacheHeaderInterceptor 精细控制
            )
            .authenticationProvider(authenticationProvider());

        return http.build();
    }
}
