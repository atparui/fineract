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
package org.apache.fineract.useradministration.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.security.service.RandomPasswordGenerator;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.organisation.office.domain.OfficeRepositoryWrapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Role;
import org.apache.fineract.useradministration.domain.RoleRepository;
import org.apache.fineract.useradministration.domain.UserDomainService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Just-in-time (JIT) user provisioning for Keycloak: creates a Fineract AppUser on first
 * successful Keycloak login when the user does not exist for the current tenant. Uses
 * default office and role from configuration and optional profile data from the JWT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "fineract.security.keycloak.enabled", havingValue = "true")
public class KeycloakJitUserProvisioningService {

    private final FineractProperties fineractProperties;
    private final OfficeRepositoryWrapper officeRepositoryWrapper;
    private final OfficeRepository officeRepository;
    private final RoleRepository roleRepository;
    private final UserDomainService userDomainService;

    public boolean isJitEnabled() {
        var keycloak = fineractProperties.getSecurity().getKeycloak();
        return keycloak != null && keycloak.getJit() != null && keycloak.getJit().isEnabled();
    }

    /**
     * Creates a Fineract user for the given username and JWT if JIT is enabled. No-op if
     * JIT is disabled or configuration is invalid. Caller should retry loading the user
     * after this returns.
     */
    @Transactional
    public void provisionUserIfAbsent(String username, Jwt jwt) {
        if (!isJitEnabled()) {
            return;
        }
        var jit = fineractProperties.getSecurity().getKeycloak().getJit();
        Office office = resolveOffice(jit.getDefaultOfficeId());
        if (office == null) {
            log.warn("Keycloak JIT: no office available for provisioning user {}", username);
            return;
        }
        Role role = roleRepository.getRoleByName(jit.getDefaultRoleName() != null ? jit.getDefaultRoleName() : "Self Service User");
        if (role == null) {
            log.warn("Keycloak JIT: default role '{}' not found; cannot provision user {}", jit.getDefaultRoleName(), username);
            return;
        }
        String email = nullSafeClaim(jwt.getClaimAsString("email"));
        String firstname = nullSafeClaim(jwt.getClaimAsString("given_name"));
        String lastname = nullSafeClaim(jwt.getClaimAsString("family_name"));
        if (firstname.isEmpty() && lastname.isEmpty()) {
            String name = nullSafeClaim(jwt.getClaimAsString("name"));
            if (!name.isEmpty()) {
                firstname = name;
            }
        }
        String password = new RandomPasswordGenerator(32).generate();
        Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("DUMMY_ROLE_NOT_USED_OR_PERSISTED_TO_AVOID_EXCEPTION"));
        User user = new User(username, password, true, true, true, true, authorities);
        AppUser appUser = new AppUser(office, user, Set.of(role), email, firstname, lastname, null, true, false, null, false);
        userDomainService.create(appUser, false);
        log.info("Keycloak JIT: provisioned Fineract user '{}' for tenant (office: {}, role: {})", username, office.getId(), role.getName());
    }

    private Office resolveOffice(Long defaultOfficeId) {
        if (defaultOfficeId != null) {
            return officeRepositoryWrapper.findOneWithNotFoundDetection(defaultOfficeId);
        }
        List<Office> offices = officeRepository.findAll(org.springframework.data.domain.Sort.by("id"));
        return offices.isEmpty() ? null : offices.get(0);
    }

    private static String nullSafeClaim(String value) {
        return value != null ? value.trim() : "";
    }
}
