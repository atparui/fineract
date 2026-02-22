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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for tenant database config returned by tenant-management-service API.
 * Matches TenantDatabaseConfigDTO from TM (GET /api/tenants/{tenantId}/database-config).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantDatabaseConfigResponse {

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("databaseUrl")
    private String databaseUrl;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("maxPoolSize")
    private Integer maxPoolSize;

    @JsonProperty("schemaServer")
    private String schemaServer;

    @JsonProperty("schemaServerPort")
    private String schemaServerPort;

    @JsonProperty("schemaName")
    private String schemaName;

    @JsonProperty("tenantName")
    private String tenantName;

    @JsonProperty("timezoneId")
    private String timezoneId;

    @JsonProperty("tenantInternalId")
    private Long tenantInternalId;
}
