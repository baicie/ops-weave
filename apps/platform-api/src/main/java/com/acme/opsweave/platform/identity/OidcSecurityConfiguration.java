package com.acme.opsweave.platform.identity;

import java.time.*;
import java.util.*;
import jakarta.servlet.SessionTrackingMode;
import org.apache.catalina.session.StandardManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.client.*;

@Configuration
@ConditionalOnProperty(name="opsweave.auth.mode", havingValue="oidc")
@EnableConfigurationProperties(OidcSettings.class)
public class OidcSecurityConfiguration {
    @Bean FileIdentityGrants identityGrants(OidcSettings settings, @Value("${server.address:}") String address) { settings.validate(address); return new FileIdentityGrants(settings); }
    @Bean OidcSessions oidcSessions(FileIdentityGrants grants, OidcSettings settings) { return new OidcSessions(grants, settings); }
    @Bean RuntimeDelegations runtimeDelegations(OidcSessions sessions) { return new RuntimeDelegations(sessions); }
    @Bean org.springframework.http.client.ClientHttpRequestInterceptor oidcNetworkBoundary(OidcSettings settings) { return OidcHttp.boundary(settings); }
    @Bean ClientRegistrationRepository oidcClients(OidcSettings settings, FileIdentityGrants validated) {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("opsweave").clientId(settings.clientId()).clientSecret(settings.clientSecret())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .scope("openid").issuerUri(settings.issuer()).authorizationUri(settings.authorizationUri()).tokenUri(settings.tokenUri()).jwkSetUri(settings.jwkSetUri())
            .redirectUri(settings.callback()).userNameAttributeName("sub").clientName("OpsWeave").build());
    }
    @Bean JwtDecoderFactory<ClientRegistration> idTokenDecoder(OidcSettings settings, org.springframework.http.client.ClientHttpRequestInterceptor oidcNetworkBoundary) {
        var rest = new RestTemplate(OidcHttp.factory()); rest.setInterceptors(List.of(oidcNetworkBoundary));
        var decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(rest).build();
        return registration -> { decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new OidcIdTokenValidator(registration), new JwtTimestampValidator(Duration.ZERO),
            jwt -> jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(Instant.now()) && jwt.getExpiresAt() != null && jwt.getIssuedAt().isBefore(jwt.getExpiresAt())
                ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")))); return decoder; };
    }
    @Bean ServletContextInitializer oidcCookies(OidcSettings settings) {
        return context -> {
            context.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE)); context.setSessionTimeout(30);
            var cookie = context.getSessionCookieConfig(); cookie.setName(settings.cookieName()); cookie.setHttpOnly(true);
            cookie.setSecure(!settings.loopbackTest()); cookie.setPath("/"); cookie.setAttribute("SameSite", "Lax");
        };
    }
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedOidcSessions() {
        return factory -> factory.addContextCustomizers(context -> { var manager = new StandardManager(); manager.setPathname(null); manager.setMaxActiveSessions(1000); context.setManager(manager); });
    }
    @Bean SecurityFilterChain oidcSecurity(HttpSecurity http, OidcSettings settings, ClientRegistrationRepository clients, OidcSessions sessions, RuntimeDelegations delegations,
            org.springframework.http.client.ClientHttpRequestInterceptor oidcNetworkBoundary, HistoryServiceFilter historyServiceFilter) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/api/v1/auth/login");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(OidcHttp.factory()).requestInterceptor(oidcNetworkBoundary)
            .messageConverters(converters -> { converters.clear(); converters.add(new FormHttpMessageConverter()); converters.add(new OAuth2AccessTokenResponseHttpMessageConverter()); })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        OAuth2AuthorizedClientRepository noTokens = new OAuth2AuthorizedClientRepository() {
            public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, org.springframework.security.core.Authentication auth, jakarta.servlet.http.HttpServletRequest request) { return null; }
            public void saveAuthorizedClient(OAuth2AuthorizedClient client, org.springframework.security.core.Authentication auth, jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) { }
            public void removeAuthorizedClient(String id, org.springframework.security.core.Authentication auth, jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) { }
        };
        return http.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(session -> session.disable()) // Application sessions are revalidated per request, not re-authenticated by Spring.
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> request.getAttribute(RuntimeDelegations.ATTR) != null || Boolean.TRUE.equals(request.getAttribute(HistoryServiceFilter.AUTHENTICATED))))
            .addFilterBefore(new OidcBoundaryFilter(settings, sessions, delegations), CsrfFilter.class)
            .addFilterBefore(historyServiceFilter, OidcBoundaryFilter.class)
            .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/session", "/api/v1/auth/login/opsweave", "/api/v1/auth/callback").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .oauth2Login(oauth -> oauth.clientRegistrationRepository(clients).authorizedClientRepository(noTokens).loginPage("/api/v1/auth/login/opsweave")
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/callback"))
                .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(input -> new DefaultOidcUser(Set.of(), input.getIdToken())))
                .successHandler((request, response, auth) -> {
                    try {
                        var user = (OidcUser) auth.getPrincipal(); sessions.establish(request, user.getIssuer().toString(), user.getSubject(), user.getExpiresAt());
                        request.changeSessionId();
                        new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository().saveToken(null, request, response);
                        SecurityContextHolder.clearContext(); response.sendRedirect(settings.publicOrigin() + "/#/inventory");
                    } catch (RuntimeException denied) { sessions.clear(request); SecurityContextHolder.clearContext(); TrustedPrincipalFilter.write(response, 403, "forbidden"); }
                })
                .failureHandler((request, response, error) -> { sessions.clear(request); TrustedPrincipalFilter.write(response, 401, "unauthenticated"); }))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies(settings.cookieName())
                .logoutSuccessHandler((request, response, auth) -> { response.setContentType("application/json"); response.getWriter().write("{\"loggedOut\":true}"); }))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> TrustedPrincipalFilter.write(response, 401, "unauthenticated"))
                .accessDeniedHandler((request, response, error) -> TrustedPrincipalFilter.write(response, 403, "forbidden")))
            .build();
    }
}
