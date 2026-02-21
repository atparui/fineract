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
package org.apache.fineract.infrastructure.security.converter;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.data.FineractJwtAuthenticationToken;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Converts a Keycloak-issued JWT into a Fineract authentication token. Uses
 * preferred_username (or sub) as the Fineract username and maps Keycloak
 * realm_access.roles to authorities. The user must exist in Fineract (m_appuser)
 * for the tenant determined by the request.
 */
@RequiredArgsConstructor
public class KeycloakJwtAuthenticationTokenConverter implements Converter<Jwt, FineractJwtAuthenticationToken> {

    private final TenantAwareJpaPlatformUserDetailsService userDetailsService;

    @Override
    @NonNull
    public FineractJwtAuthenticationToken convert(@NonNull Jwt jwt) {
        String username = getUsername(jwt);
        try {
            UserDetails user = userDetailsService.loadUserByUsername(username);
            Collection<GrantedAuthority> authorities = mergeAuthorities(jwt, user.getAuthorities());
            return new FineractJwtAuthenticationToken(jwt, authorities, user);
        } catch (UsernameNotFoundException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN), ex);
        }
    }

    private String getUsername(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return jwt.getSubject();
    }

    private Collection<GrantedAuthority> mergeAuthorities(Jwt jwt, Collection<? extends GrantedAuthority> userAuthorities) {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);
        Collection<GrantedAuthority> realmRoles = extractRealmRoles(jwt);
        return Stream.of(scopeAuthorities, realmRoles, userAuthorities)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof java.util.Map)) {
            return Collections.emptyList();
        }
        Object roles = ((java.util.Map<String, Object>) realmAccess).get("roles");
        if (!(roles instanceof Collection)) {
            return Collections.emptyList();
        }
        return ((Collection<String>) roles).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
