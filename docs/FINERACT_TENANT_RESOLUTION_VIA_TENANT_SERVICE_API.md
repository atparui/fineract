# Fineract Tenant Resolution via Tenant-Management-Service API Only

This document describes **using the tenant-management-service (TM) as the only source for tenant data** from Fineract’s perspective: Fineract **does not connect to any tenant store database**. It resolves tenants by calling the TM API. This isolates all tenant and connection data inside TM and allows TM to adapt its API and internal logic to Fineract’s needs.

See also: [FINERACT_TENANT_CREATION_AND_TM_ATPARUI.md](./FINERACT_TENANT_CREATION_AND_TM_ATPARUI.md), [FINERACT_TENANT_VIEWS_TENANT_SERVICE.md](./FINERACT_TENANT_VIEWS_TENANT_SERVICE.md) (alternative: views in TM DB).

---

## 1. Goal

- **No tenant DB in Fineract:** Fineract does not maintain or connect to a “tenant store” database (no `hikariTenantDataSource` for tenant resolution, no fineract-tenants schema, no views in another DB).
- **TM as single source of truth:** All tenant metadata and connection details live in tenant-management-service. Fineract gets them only via **HTTP calls** to TM (e.g. tm.atparui.com or internal URL).
- **Isolation:** Tenant DB connection strings and credentials are never stored or read by Fineract; only TM holds them. Fineract only receives what it needs to connect to each tenant’s **business** database for request handling.
- **TM can suit Fineract:** TM can expose APIs or response shapes that match what Fineract expects (tenant identifier, name, timezone, connection host/port/name/user/password, pool defaults), so Fineract’s integration stays simple.

---

## 2. Current vs Target

| Aspect | Current (DB / views) | Target (API only) |
|--------|----------------------|--------------------|
| Tenant resolution | Fineract queries a DB (tenant store or TM DB views) | Fineract calls TM API |
| Tenant list (startup) | From tenant store DB via `TenantDetailsService` | From TM API (e.g. GET /api/tenants + database-config per tenant or a batch endpoint) |
| Connection details | Read from `tenants` + `tenant_server_connections` (tables or views) | Returned in TM API response (e.g. `TenantDatabaseConfigDTO` or Fineract-shaped DTO) |
| Fineract config | Tenant store DB URL, user, password | TM base URL, optional auth (API key, mTLS, or internal network) |
| Liquibase on tenant store | Skipped when “managed externally” (views) | Not applicable; no tenant store in Fineract |

---

## 3. What Fineract Needs

Fineract uses two abstractions that today read from the tenant store DB:

1. **AuthTenantDetailsService** (per-request): `loadTenantById(tenantIdentifier, isReport)` → `FineractPlatformTenant`.
   - Used by security filters and Keycloak config to resolve the tenant for each request (e.g. from `Fineract-Platform-TenantId` or JWT).
   - Needs: tenant id, name, timezone_id, and **connection** (schema_server, schema_server_port, schema_name, schema_username, schema_password, pool settings, optional readonly/master_password_hash).

2. **TenantDetailsService** (startup / core): `loadTenantById(tenantId)`, `findAllTenants()` → `List<FineractPlatformTenant>`.
   - Used by `TenantDatabaseUpgradeService` to get the list of tenants and run Liquibase on each tenant’s business DB.
   - Needs: same shape as above for each tenant.

So whatever Fineract uses (DB or API) must supply, per tenant:

- **Tenant:** id, identifier, name, timezone_id.
- **Connection:** host, port, database name (schema_name), username, password, optional connection parameters, pool defaults; optionally readonly connection and master_password_hash.

TM’s existing **GET /api/tenants/{tenantId}/database-config** returns `TenantDatabaseConfigDTO`: tenantId, databaseUrl, username, password, maxPoolSize, connectionTimeout, validationQuery, driverType, keycloakBaseUrl, realmName, clients. That is enough to **derive** connection details if Fineract parses `databaseUrl` (e.g. `jdbc:postgresql://host:5432/dbname` → host, port, schema_name). Name and timezone can be defaulted (e.g. name = tenantId, timezone = "UTC") unless TM adds them.

---

## 4. Tenant-Management-Service Side (Adjust to Suit Fineract)

TM can stay as-is and Fineract can map from the existing DTO, or TM can add Fineract-friendly fields/endpoints so that “tenant management service can have its internal logic adjusted to suit the requirement of the fineract”.

### Option A – Use existing API only

- **GET /api/tenants/{tenantId}/database-config** already returns tenantId, databaseUrl, username, password, etc.
- Fineract parses `databaseUrl` to get host, port, database name; maps to `FineractPlatformTenant` + `FineractPlatformTenantConnection` with default name/timezone/pool values.
- **GET /api/tenants** returns the list of tenants (ids and identifiers). Fineract then calls database-config for each tenant to get connection details (or caches after first call). No TM change.

### Option B – TM adds Fineract-shaped fields or endpoint (recommended for “suit Fineract”)

TM can add one or both of:

1. **Extra fields on existing response**  
   In `TenantDatabaseConfigDTO` (or a wrapper), add optional fields that match Fineract’s expectations so Fineract doesn’t parse URLs:
   - `schemaServer`, `schemaServerPort`, `schemaName` (or keep schema_name = database name)
   - `tenantName`, `timezoneId`
   - Optionally pool defaults Fineract uses (e.g. initialSize, maxActive, etc.).

2. **Dedicated “Fineract config” endpoint**  
   e.g. **GET /api/tenants/{tenantId}/fineract-config** (or **GET /api/tenants/by-identifier/{identifier}/fineract-config**) that returns a JSON shape tailored to Fineract:
   - Tenant: id, identifier, name, timezoneId.
   - Connection: schemaServer, schemaServerPort, schemaName, schemaUsername, schemaPassword, schemaConnectionParameters, and pool/readonly/masterPasswordHash if needed.

TM’s internal logic stays in TM (same DB, same tenant table); only the **API response** is adjusted so Fineract gets exactly what it needs without parsing or guessing.

### Option C – Batch endpoint for “all tenants” (for startup)

To avoid N+1 (GET /api/tenants then one database-config per tenant), TM can expose:

- **GET /api/tenants/database-configs** or **GET /api/tenants?includeDatabaseConfig=true**  
  Returns a list of tenant records each including database connection details (e.g. list of `TenantDatabaseConfigDTO` or Fineract-shaped objects).  
  Fineract’s `TenantDetailsService` (TM API implementation) then calls this once at startup and builds `List<FineractPlatformTenant>`.

---

## 5. Fineract Side (Use TM Only)

### 5.1 New implementations (no tenant store DB)

1. **AuthTenantDetailsServiceTmApi** (implements `AuthTenantDetailsService`)
   - On `loadTenantById(tenantIdentifier, isReport)`:
     - Call TM: e.g. `GET {tmBaseUrl}/api/tenants/{tenantIdentifier}/database-config` (or `/fineract-config` if TM adds it).
     - Map response to `FineractPlatformTenant` (id, identifier, name, timezoneId, connection).
     - Build `FineractPlatformTenantConnection` from DTO (parse databaseUrl if needed, or use TM’s schemaServer/schemaServerPort/schemaName if provided).
   - Throw `InvalidTenantIdentifierException` if TM returns 404 or empty.
   - Optional: cache by tenantIdentifier (e.g. short TTL) to reduce calls.

2. **TenantDetailsServiceTmApi** (implements `TenantDetailsService` in core)
   - `loadTenantById(tenantId)`: call TM database-config (or fineract-config) for that tenant, map to `FineractPlatformTenant`.
   - `findAllTenants()`:  
     - Either call **GET /api/tenants** and then for each tenant call database-config (with optional caching),  
     - Or call a single batch endpoint if TM exposes one (Option C above).

3. **Configuration**
   - New properties, e.g.:
     - `fineract.tenant.resolution=tenant-service-api` (vs `jdbc` or default).
     - `fineract.tenant.service.base-url=https://tm.atparui.com` (or internal URL).
     - Optional: `fineract.tenant.service.api-key`, timeout, etc.
   - When `resolution=tenant-service-api`:
     - Wire **AuthTenantDetailsServiceTmApi** and **TenantDetailsServiceTmApi** as the implementations used by security and by `TenantDatabaseUpgradeService`.
     - **Do not** create or use `hikariTenantDataSource` for tenant resolution (no tenant store DB). Optionally make the tenant-store datasource a no-op or remove its use for tenant resolution.
     - `TenantDatabaseUpgradeService`: skip `upgradeTenantStore()` (no tenant store); keep `upgradeIndividualTenants()` using `TenantDetailsService.findAllTenants()` from TM API.

### 5.2 What gets removed / simplified in Fineract

- No Liquibase run on a “tenant store” in Fineract.
- No config for tenant store DB URL/username/password when using TM API.
- No views, no fineract-tenants schema, no shared DB with TM for tenant tables.  
Tenant data and connection details live only in TM; Fineract only holds TM base URL and optional auth.

---

## 6. Flow Summary

1. **Request arrives** at Fineract with tenant identifier (header or JWT).
2. Fineract calls **AuthTenantDetailsService.loadTenantById(identifier)** → implementation **AuthTenantDetailsServiceTmApi** calls TM **GET /api/tenants/{identifier}/database-config** (or fineract-config).
3. TM returns connection details (and optionally name, timezone). Fineract maps to `FineractPlatformTenant` and uses the connection to talk to that tenant’s **business** database.
4. **Startup:** `TenantDatabaseUpgradeService` calls **TenantDetailsService.findAllTenants()** → **TenantDetailsServiceTmApi** calls TM (list + database-config per tenant, or one batch). For each tenant, Fineract runs Liquibase on the tenant’s business DB only; no tenant store DB.

Tenant creation remains only in TM (tm.atparui.com). New tenants are visible to Fineract as soon as TM returns them from the API; no sync or views required.

---

## 7. Comparison with View-Based Approach

| | Views in TM DB (current doc) | TM API only (this doc) |
|---|------------------------------|-------------------------|
| Fineract tenant store DB | Points at TM DB; reads views | None |
| Fineract config | TM DB URL + tenant-store-managed-externally | TM base URL + resolution=tenant-service-api |
| Network | Fineract → TM DB (SQL) | Fineract → TM API (HTTP) |
| Isolation | Fineract still has DB credentials for TM DB | Full: no tenant/connection data in Fineract |
| TM changes | Liquibase views; optional | Optional richer DTO or endpoints |
| Latency | One DB round-trip per resolution | One HTTP call per resolution (cacheable) |

Using TM **only** via API gives the cleanest isolation and lets TM own all “tenant management” logic and data; Fineract just consumes an API that TM can shape for Fineract’s requirements.

---

## 8. Implementation Summary (Done)

### TM (tenant-management-service)

- **TenantDatabaseConfigDTO** includes Fineract-friendly fields: `schemaServer`, `schemaServerPort`, `schemaName`, `tenantName`, `timezoneId`, `tenantInternalId` (populated from tenant entity in `convertToDatabaseConfigDTO`).
- **GET /api/tenants/{tenantId}/database-config** — unchanged; response now includes the above fields.
- **GET /api/tenants/database-configs** — returns `List<TenantDatabaseConfigDTO>` for all active tenants (used by Fineract for batch resolution).

### Fineract

- **Configuration (application.properties / env):**
  - `fineract.tenant.resolution` = `jdbc` (default) | `tenant-service-api`
  - `fineract.tenant.service-base-url` = base URL of TM (e.g. `https://tm.atparui.com`). Required when `resolution=tenant-service-api`.
  - Env overrides: `FINERACT_TENANT_RESOLUTION`, `FINERACT_TENANT_SERVICE_BASE_URL`
- **When `resolution=tenant-service-api`:**
  - **TenantServiceApiClient** calls TM `GET /api/tenants/{tenantId}/database-config` and `GET /api/tenants/database-configs`.
  - **AuthTenantDetailsServiceTmApi** and **TenantDetailsServiceTmApi** resolve tenants via TM API; no tenant store DB.
  - **HikariCpConfig** is not loaded; **TenantServiceApiDataSourceConfig** provides a stub `hikariTenantDataSource` (never used for connections).
  - **TenantDatabaseUpgradeService** skips `upgradeTenantStore()` (no Liquibase on tenant store); still runs Liquibase on each tenant’s business DB using `TenantDetailsService.findAllTenants()` from TM.
- **When `resolution=jdbc` (default):** Existing behaviour: **AuthTenantDetailsServiceJdbc**, **JdbcTenantDetailsService**, and **HikariCpConfig** (tenant store DB) are used.

### Enabling API-only mode

1. Set `fineract.tenant.resolution=tenant-service-api` and `fineract.tenant.service-base-url=<TM_BASE_URL>` (e.g. `https://tm.atparui.com`).
2. Ensure TM is reachable from Fineract and exposes `/api/tenants/{tenantId}/database-config` and `/api/tenants/database-configs` (no auth required for these internal endpoints in the current implementation).
3. Do not configure a tenant store datasource for Fineract; tenant resolution is done entirely via TM API.

---

## 9. Suggested Next Steps (Optional)

1. **TM (optional):** Add Fineract-friendly fields to `TenantDatabaseConfigDTO` or add **GET /api/tenants/{id}/fineract-config** and, if desired, **GET /api/tenants/database-configs** for batch.
2. **Fineract:** Implement `AuthTenantDetailsServiceTmApi` and `TenantDetailsServiceTmApi`, add `fineract.tenant.resolution` and TM base URL config, and conditionally wire them when resolution is `tenant-service-api`.
3. **Fineract:** When resolution is TM API, do not create or use a tenant store datasource for resolution; skip tenant-store Liquibase.
4. **Docs / ops:** Document that when using “tenant-service API” mode, Fineract has no tenant DB and all tenant management is done via tm.atparui.com and TM’s internal logic.

This keeps the platform consistent (single tenant store in TM) and isolates tenant DB connections entirely inside tenant-management-service while Fineract uses it like any other consumer of TM.
