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
package org.apache.fineract.infrastructure.security.service;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.security.exception.InvalidTenantIdentifierException;
import org.apache.fineract.infrastructure.tenant.api.TenantDatabaseConfigResponse;
import org.apache.fineract.infrastructure.tenant.api.TenantServiceApiClient;
import org.apache.fineract.infrastructure.tenant.api.TenantServiceApiMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Resolves tenant details via tenant-management-service API (no tenant DB in Fineract).
 */
@Service
@ConditionalOnProperty(name = "fineract.tenant.resolution", havingValue = "tenant-service-api")
public class AuthTenantDetailsServiceTmApi implements AuthTenantDetailsService {

    private final TenantServiceApiClient tenantServiceApiClient;

    public AuthTenantDetailsServiceTmApi(TenantServiceApiClient tenantServiceApiClient) {
        this.tenantServiceApiClient = tenantServiceApiClient;
    }

    @Override
    @Cacheable(value = "tenantsById")
    public FineractPlatformTenant loadTenantById(String tenantIdentifier, boolean isReport) {
        TenantDatabaseConfigResponse config = tenantServiceApiClient.getDatabaseConfig(tenantIdentifier);
        FineractPlatformTenant tenant = TenantServiceApiMapper.toFineractPlatformTenant(config);
        if (tenant == null) {
            throw new InvalidTenantIdentifierException("The tenant identifier: " + tenantIdentifier + " is not valid.");
        }
        return tenant;
    }
}
