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
package org.apache.fineract.infrastructure.tenant.api;

import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Client for tenant-management-service API. Fetches tenant database config by ID or all configs.
 */
@Component
@ConditionalOnProperty(name = "fineract.tenant.resolution", havingValue = "tenant-service-api")
public class TenantServiceApiClient {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceApiClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;

    public TenantServiceApiClient(FineractProperties fineractProperties) {
        this.baseUrl = fineractProperties.getTenant().getServiceBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("fineract.tenant.service-base-url is required when fineract.tenant.resolution=tenant-service-api");
        }
        this.restTemplate = new RestTemplate();
    }

    /**
     * GET /api/tenants/{tenantId}/database-config
     */
    public TenantDatabaseConfigResponse getDatabaseConfig(String tenantId) {
        String url = baseUrl.replaceAll("/$", "") + "/api/tenants/" + tenantId + "/database-config";
        try {
            ResponseEntity<TenantDatabaseConfigResponse> response = restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Tenant not found: {}", tenantId);
            return null;
        }
    }

    /**
     * GET /api/tenants/database-configs
     */
    public List<TenantDatabaseConfigResponse> getAllDatabaseConfigs() {
        String url = baseUrl.replaceAll("/$", "") + "/api/tenants/database-configs";
        try {
            ResponseEntity<List<TenantDatabaseConfigResponse>> response = restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to fetch all tenant database configs from TM: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
