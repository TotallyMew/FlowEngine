package dev.flowengine.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
class SecurityConfiguration {
    @Bean
    UserDetailsService operator(@Value("${flow.operator.username}") String username,
                                @Value("${flow.operator.password}") String password) {
        if (!username.matches("[A-Za-z0-9_.@-]{1,80}") || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Configure a valid FLOW_OPERATOR_USERNAME and a FLOW_OPERATOR_PASSWORD of 12–72 bytes.");
        }
        var encoder = new BCryptPasswordEncoder(12);
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password("{bcrypt}" + encoder.encode(password)).roles("OPERATOR").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) {
        var webhook = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/{workflowId}");
        http.csrf(csrf -> csrf.ignoringRequestMatchers(webhook))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(webhook).permitAll()
                        .requestMatchers("/actuator/health", "/api/session/csrf", "/api/session/login", "/error").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                problem(response, 401, "Sign in to continue."))
                        .accessDeniedHandler((request, response, failure) ->
                                problem(response, 403, "Access denied. Refresh the page and try again.")))
                .formLogin(login -> login.loginProcessingUrl("/api/session/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, failure) -> problem(response, 401, "Invalid username or password.")))
                .logout(logout -> logout.logoutUrl("/api/session/logout").deleteCookies("FLOWSESSION")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    private static void problem(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + message + "\"}");
    }
}
