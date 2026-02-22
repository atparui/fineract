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

import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenantConnection;

/**
 * Maps tenant-management-service API response to Fineract tenant domain.
 */
public final class TenantServiceApiMapper {

    private static final int DEFAULT_MAX_ACTIVE = 20;
    private static final int DEFAULT_MIN_IDLE = 1;
    private static final int DEFAULT_MAX_IDLE = 8;

    private TenantServiceApiMapper() {}

    public static FineractPlatformTenant toFineractPlatformTenant(TenantDatabaseConfigResponse dto) {
        if (dto == null || StringUtils.isBlank(dto.getTenantId())) {
            return null;
        }
        Long id = dto.getTenantInternalId() != null ? dto.getTenantInternalId() : 0L;
        String tenantIdentifier = dto.getTenantId();
        String name = StringUtils.isNotBlank(dto.getTenantName()) ? dto.getTenantName() : tenantIdentifier;
        String timezoneId = StringUtils.isNotBlank(dto.getTimezoneId()) ? dto.getTimezoneId() : "UTC";
        FineractPlatformTenantConnection connection = toConnection(dto);
        return new FineractPlatformTenant(id, tenantIdentifier, name, timezoneId, connection);
    }

    private static FineractPlatformTenantConnection toConnection(TenantDatabaseConfigResponse dto) {
        String schemaServer = dto.getSchemaServer();
        String schemaServerPort = dto.getSchemaServerPort();
        String schemaName = dto.getSchemaName();
        if ((StringUtils.isBlank(schemaServer) || StringUtils.isBlank(schemaName)) && StringUtils.isNotBlank(dto.getDatabaseUrl())) {
            parseFromJdbcUrl(dto.getDatabaseUrl(), dto);
            schemaServer = dto.getSchemaServer();
            schemaServerPort = dto.getSchemaServerPort();
            schemaName = dto.getSchemaName();
        }
        if (StringUtils.isBlank(schemaName)) {
            schemaName = dto.getTenantId();
        }
        if (StringUtils.isBlank(schemaServer)) {
            schemaServer = "localhost";
        }
        if (StringUtils.isBlank(schemaServerPort)) {
            schemaServerPort = "3306";
        }
        String schemaUsername = StringUtils.isNotBlank(dto.getUsername()) ? dto.getUsername() : "";
        String schemaPassword = dto.getPassword() != null ? dto.getPassword() : "";
        int maxActive = dto.getMaxPoolSize() != null && dto.getMaxPoolSize() > 0 ? dto.getMaxPoolSize() : DEFAULT_MAX_ACTIVE;
        Long connectionId = dto.getTenantInternalId() != null ? dto.getTenantInternalId() : 1L;

        return new FineractPlatformTenantConnection(connectionId, schemaName, schemaServer, schemaServerPort, null,
                schemaUsername, schemaPassword, false, 1, 30000L, false, 60, false, 0, maxActive, DEFAULT_MIN_IDLE,
                DEFAULT_MAX_IDLE, 0, 5000, 60000, true, null, null, null, null, null, null, null);
    }

    private static void parseFromJdbcUrl(String jdbcUrl, TenantDatabaseConfigResponse dto) {
        // e.g. jdbc:mysql://host:3306/dbname or jdbc:postgresql://host:5432/dbname
        if (StringUtils.isBlank(jdbcUrl) || !jdbcUrl.contains("://")) {
            return;
        }
        String rest = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String db = slash >= 0 && slash < rest.length() - 1 ? rest.substring(slash + 1) : null;
        if (db != null && db.contains("?")) {
            db = db.substring(0, db.indexOf('?'));
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        String port = colon >= 0 && colon < hostPort.length() - 1 ? hostPort.substring(colon + 1) : "3306";
        dto.setSchemaServer(host);
        dto.setSchemaServerPort(port);
        if (StringUtils.isNotBlank(db)) {
            dto.setSchemaName(db);
        }
    }
}
