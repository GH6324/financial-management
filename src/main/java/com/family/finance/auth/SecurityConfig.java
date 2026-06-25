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

    @Bean
    public AuthenticationFailureHandler failureHandler() {
        return new SimpleUrlAuthenticationFailureHandler("/login?error");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RememberMeServices rememberMeServices) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/login", "/login/error", "/static/**", "/css/**", "/vendor/**", "/img/**", "/uploads/**", "/js/**", "/manifest.webmanifest", "/favicon.ico", "/error", "/health", "/actuator/health").permitAll()
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
            .headers(h -> h
                .frameOptions(f -> f.disable())
                .cacheControl(c -> c.disable())  // 改由 WebMvcConfig+CacheHeaderInterceptor 精细控制
            )
            .authenticationProvider(authenticationProvider());

        return http.build();
    }
}
