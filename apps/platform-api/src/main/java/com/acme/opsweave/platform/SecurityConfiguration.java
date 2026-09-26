package com.acme.opsweave.platform;

import com.acme.opsweave.platform.identity.TrustedPrincipalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${opsweave.auth.mode:closed}' != 'oidc'")
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TrustedPrincipalFilter principalFilter, com.acme.opsweave.platform.identity.HistoryServiceFilter historyServiceFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .addFilterBefore(principalFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(historyServiceFilter, TrustedPrincipalFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, exception) ->
                    TrustedPrincipalFilter.write(response, 401, "unauthenticated"))
                .accessDeniedHandler((request, response, exception) ->
                    TrustedPrincipalFilter.write(response, 403, "forbidden")))
            .build();
    }
}
