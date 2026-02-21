/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.security.config;

import static org.apache.fineract.infrastructure.security.vote.SelfServiceUserAuthorizationManager.selfServiceUserAuthManager;
import static org.springframework.security.authorization.AuthenticatedAuthorizationManager.fullyAuthenticated;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;

import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.filters.CallerIpTrackingFilter;
import org.apache.fineract.infrastructure.core.filters.CorrelationHeaderFilter;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreFilter;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreHelper;
import org.apache.fineract.infrastructure.core.filters.RequestResponseFilter;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.instancemode.filter.FineractInstanceModeApiFilter;
import org.apache.fineract.infrastructure.jobs.filter.LoanCOBApiFilter;
import org.apache.fineract.infrastructure.jobs.filter.LoanCOBFilterHelper;
import org.apache.fineract.infrastructure.security.converter.KeycloakJwtAuthenticationTokenConverter;
import org.apache.fineract.infrastructure.security.filter.BusinessDateFilter;
import org.apache.fineract.infrastructure.security.filter.TenantAwareAuthenticationFilter;
import org.apache.fineract.infrastructure.security.filter.TwoFactorAuthenticationFilter;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.infrastructure.security.service.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Configures Fineract as an OAuth2 resource server that validates JWTs issued by
 * Keycloak (e.g. auth.atparui.com, nbk-demo realm). When enabled, Basic Auth and
 * the built-in OAuth2 authorization server are disabled; all API access requires
 * a Bearer token from Keycloak. Tenant is taken from the JWT claim "tenant" or
 * from the request header Fineract-Platform-TenantId.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "fineract.security.keycloak.enabled", havingValue = "true")
@EnableConfigurationProperties(FineractProperties.class)
public class KeycloakResourceServerConfig {

    private static final PathPatternRequestMatcher.Builder API_MATCHER = PathPatternRequestMatcher.withDefaults();
    private static final String ALL_FUNCTIONS = "ALL_FUNCTIONS";
    private static final String ALL_FUNCTIONS_READ = "ALL_FUNCTIONS_READ";
    private static final String ALL_FUNCTIONS_WRITE = "ALL_FUNCTIONS_WRITE";

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private TenantAwareJpaPlatformUserDetailsService userDetailsService;
    @Autowired
    private FineractProperties fineractProperties;
    @Autowired
    private AuthTenantDetailsService tenantDetailsService;
    @Autowired
    private BusinessDateReadPlatformService businessDateReadPlatformService;
    @Autowired
    private MDCWrapper mdcWrapper;
    @Autowired
    private FineractRequestContextHolder fineractRequestContextHolder;
    @Autowired(required = false)
    private LoanCOBFilterHelper loanCOBFilterHelper;
    @Autowired
    private IdempotencyStoreHelper idempotencyStoreHelper;

    @Bean
    @Order(1)
    public SecurityFilterChain keycloakPublicEndpoints(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger-ui/**", "/fineract.json", "/actuator/**", "/legacy-docs/apiLive.htm")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        if (fineractProperties.getSecurity().getCors().isEnabled()) {
            http.cors(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain keycloakApiFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(API_MATCHER.matcher("/api/**"))
                .authorizeHttpRequests(auth -> {
                    List<AuthorizationManager<RequestAuthorizationContext>> managers = new ArrayList<>();
                    managers.add(fullyAuthenticated());
                    if (fineractProperties.getSecurity().getTwoFactor().isEnabled()) {
                        managers.add(hasAuthority("TWOFACTOR_AUTHENTICATED"));
                    }
                    if (fineractProperties.getModule().getSelfService().isEnabled()) {
                        auth.requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/self/authentication")).permitAll()
                                .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/self/registration")).permitAll()
                                .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/self/registration/user")).permitAll();
                        managers.add(selfServiceUserAuthManager());
                    }
                    auth.requestMatchers(API_MATCHER.matcher(HttpMethod.OPTIONS, "/api/**")).permitAll()
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/echo")).permitAll()
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/authentication")).permitAll()
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.PUT, "/api/*/instance-mode")).permitAll()
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/twofactor/validate")).fullyAuthenticated()
                            .requestMatchers(API_MATCHER.matcher("/api/*/twofactor")).fullyAuthenticated()
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.GET, "/api/*/businessdate/*"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_READ, "READ_BUSINESS_DATE")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/businessdate"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_WRITE, "UPDATE_BUSINESS_DATE")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.GET, "/api/*/externalevents/configuration"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_READ, "READ_EXTERNAL_EVENT_CONFIGURATION")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.PUT, "/api/*/externalevents/configuration"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_WRITE, "UPDATE_EXTERNAL_EVENT_CONFIGURATION")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.GET, "/api/*/caches"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_READ, "READ_CACHE")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.PUT, "/api/*/caches"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_WRITE, "UPDATE_CACHE")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.GET, "/api/*/currencies"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_READ, "READ_CURRENCY")
                            .requestMatchers(API_MATCHER.matcher(HttpMethod.POST, "/api/*/currencies"))
                            .hasAnyAuthority(ALL_FUNCTIONS, ALL_FUNCTIONS_WRITE, "UPDATE_CURRENCY")
                            .requestMatchers(API_MATCHER.matcher("/api/**"))
                            .access(allOf(managers.toArray(new AuthorizationManager[0])));
                })
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(keycloakJwtDecoder())
                        .jwtAuthenticationConverter(keycloakJwtAuthenticationTokenConverter())))
                .addFilterAfter(tenantAwareAuthenticationFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(businessDateFilter(), TenantAwareAuthenticationFilter.class)
                .addFilterAfter(requestResponseFilter(), ExceptionTranslationFilter.class)
                .addFilterAfter(correlationHeaderFilter(), RequestResponseFilter.class)
                .addFilterAfter(fineractInstanceModeApiFilter(), CorrelationHeaderFilter.class);

        if (loanCOBFilterHelper != null) {
            http.addFilterAfter(loanCOBApiFilter(), FineractInstanceModeApiFilter.class)
                    .addFilterAfter(idempotencyStoreFilter(), LoanCOBApiFilter.class);
        } else {
            http.addFilterAfter(idempotencyStoreFilter(), FineractInstanceModeApiFilter.class);
        }
        if (fineractProperties.getIpTracking().isEnabled()) {
            http.addFilterAfter(callerIpTrackingFilter(), RequestResponseFilter.class);
        }
        if (fineractProperties.getSecurity().getTwoFactor().isEnabled()) {
            http.addFilterAfter(twoFactorAuthenticationFilter(), CorrelationHeaderFilter.class);
        }
        if (fineractProperties.getSecurity().getCors().isEnabled()) {
            http.cors(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    public JwtDecoder keycloakJwtDecoder() {
        var keycloak = fineractProperties.getSecurity().getKeycloak();
        if (keycloak == null) {
            throw new IllegalStateException("fineract.security.keycloak.* properties must be set when keycloak is enabled");
        }
        String issuerUri = keycloak.getIssuerUri();
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("fineract.security.keycloak.issuer-uri must be set when keycloak is enabled");
        }
        return JwtDecoders.createFromIssuerLocation(issuerUri);
    }

    @Bean
    public KeycloakJwtAuthenticationTokenConverter keycloakJwtAuthenticationTokenConverter() {
        return new KeycloakJwtAuthenticationTokenConverter(userDetailsService);
    }

    @Bean
    public BearerTokenResolver keycloakBearerTokenResolver() {
        return new DefaultBearerTokenResolver();
    }

    @Bean
    public OncePerRequestFilter tenantAwareAuthenticationFilter() {
        return new TenantAwareAuthenticationFilter(keycloakBearerTokenResolver(), tenantDetailsService);
    }

    @Bean
    public OncePerRequestFilter businessDateFilter() {
        return new BusinessDateFilter(businessDateReadPlatformService);
    }

    private RequestResponseFilter requestResponseFilter() {
        return new RequestResponseFilter();
    }

    private LoanCOBApiFilter loanCOBApiFilter() {
        return new LoanCOBApiFilter(loanCOBFilterHelper);
    }

    private TwoFactorAuthenticationFilter twoFactorAuthenticationFilter() {
        TwoFactorService twoFactorService = applicationContext.getBean(TwoFactorService.class);
        return new TwoFactorAuthenticationFilter(twoFactorService);
    }

    private FineractInstanceModeApiFilter fineractInstanceModeApiFilter() {
        return new FineractInstanceModeApiFilter(fineractProperties);
    }

    private IdempotencyStoreFilter idempotencyStoreFilter() {
        return new IdempotencyStoreFilter(fineractRequestContextHolder, idempotencyStoreHelper, fineractProperties);
    }

    private CorrelationHeaderFilter correlationHeaderFilter() {
        return new CorrelationHeaderFilter(fineractProperties, mdcWrapper);
    }

    private CallerIpTrackingFilter callerIpTrackingFilter() {
        return new CallerIpTrackingFilter(fineractProperties);
    }

    @Bean
    public CorsConfigurationSource keycloakCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        FineractProperties.CorsProperties cors = fineractProperties.getSecurity().getCors();
        config.setAllowedOriginPatterns(cors.getAllowedOriginPatterns());
        config.setAllowedMethods(cors.getAllowedMethods());
        config.setAllowedHeaders(cors.getAllowedHeaders());
        config.setExposedHeaders(cors.getExposedHeaders());
        config.setAllowCredentials(cors.isAllowCredentials());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
