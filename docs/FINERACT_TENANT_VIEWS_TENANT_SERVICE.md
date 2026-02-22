# Fineract Tenant Store via Tenant-Service Views

This document describes using **tenant-service** (tenant-management-service database) as the **single source of truth** for tenants. Fineract reads tenant data **only via SELECT** from two **views** in the tenant-service database that look like Fineract’s `tenants` and `tenant_server_connections` tables. Tenant **creation** happens only via tm.atparui.com; the fineract-tenants schema can be retired.

See also: [FINERACT_TENANT_CREATION_AND_TM_ATPARUI.md](./FINERACT_TENANT_CREATION_AND_TM_ATPARUI.md).

---

## 1. Goal

- **Single tenant store:** Only tenant-service holds tenant data; no separate fineract-tenants DB.
- **Fineract:** Point its tenant datasource (`hikariTenantDataSource`) at the **tenant-service database** and run **read-only** queries against views `tenants` and `tenant_server_connections`.
- **Tenant creation:** Only via tm.atparui.com (tenant-management-service API). No INSERT/UPDATE by Fineract on the tenant store.
- **Maintenance:** One place to manage tenants; easier operations and consistency.

---

## 2. How Fineract Loads Tenants

Fineract uses:

- **`AuthTenantDetailsServiceJdbc`** with a `JdbcTemplate` backed by **`hikariTenantDataSource`**.
- **`TenantMapper`** runs:  
  `SELECT <columns> FROM tenants t LEFT JOIN tenant_server_connections ts ON t.oltp_id = ts.id WHERE t.identifier = ?`  
  (For report mode it uses `t.report_id = ts.id`.)

So the database must expose:

1. **`tenants`** (table or view) with: `id`, `identifier`, `name`, `timezone_id`, `oltp_id`, `report_id`.
2. **`tenant_server_connections`** (table or view) with: `id`, `schema_name`, `schema_server`, `schema_server_port`, `schema_connection_parameters`, `auto_update`, `schema_username`, `schema_password`, pool columns, readonly columns, `master_password_hash`.

The exact column set is defined in `TenantMapper` and in Fineract’s tenant-store Liquibase (e.g. `fineract-provider/.../tenant-store/parts/0001_initial_schema.xml`, `0004_readonly_database_connection.xml`, `0007_encrypt_existing_tenant_passwords.xml`). The views below match that contract.

---

## 3. Mapping Tenant-Service → Fineract Shape

- **One tenant in tenant-service** → one row in the view `tenants` and one row in the view `tenant_server_connections`, with **same id** used so that `t.oltp_id = ts.id` and `t.report_id = ts.id` (both set to tenant id).
- **Identifier:** `COALESCE(tenant_id, tenant_key)` from tenant-service `tenant` table.
- **Connection fields:** From `tenant`: `database_host` → `schema_server`, `database_port` → `schema_server_port`, `database_name` → `schema_name`, `database_username` → `schema_username`, `database_password` → `schema_password`. Pool and readonly columns use defaults or NULL where tenant-service has no equivalent.
- **Timezone:** Tenant-service may not have `timezone_id`; view uses a default (e.g. `'UTC'`) or a future column if added.

---

## 4. View Creation: Liquibase (Automatic) or Manual DDL

### 4.0 Automatic: tenant-management-service Liquibase

The **tenant-management-service** creates the Fineract-compatible views **on startup** via Liquibase:

- Changelog: `config/liquibase/changelog/20260221000001_fineract_tenant_views.xml` (included in `master.xml`).
- When the service starts, Liquibase runs against its own database and creates (or replaces) the views `tenant_server_connections` and `tenants`.
- No manual SQL is required for new deployments; ensure the tenant table exists and has the expected columns (see §4.3).

If you need to run the view DDL manually (e.g. one-off migration), use the SQL in §4.1–4.2 below or the script `tenant-management-service/docs/FINERACT_TENANT_VIEWS.sql`.

---

### 4.1–4.3 Manual PostgreSQL View DDL (reference)

The following is the same logic as in the Liquibase changelog. Run only if not using tenant-management-service Liquibase. Assumes the tenant table has columns: `id`, `tenant_key`, `tenant_id`, `name`, `database_host`, `database_port`, `database_name`, `database_url`, `database_username`, `database_password`, `schema_name`, `is_active`.

#### View: `tenant_server_connections`

One row per tenant; `id` = tenant id so the join `t.oltp_id = ts.id` works.

```sql
-- View compatible with Fineract's tenant_server_connections (read-only use by Fineract)
CREATE OR REPLACE VIEW tenant_server_connections AS
SELECT
    t.id AS id,
    COALESCE(NULLIF(TRIM(t.database_host), ''), 'localhost') AS schema_server,
    COALESCE(t.database_name, t.schema_name, 'public') AS schema_name,
    COALESCE(TRIM(t.database_port::TEXT), '5432') AS schema_server_port,
    COALESCE(t.database_username, '') AS schema_username,
    COALESCE(t.database_password, '') AS schema_password,
    0 AS auto_update,
    5 AS pool_initial_size,
    30000 AS pool_validation_interval,
    1 AS pool_remove_abandoned,
    60 AS pool_remove_abandoned_timeout,
    1 AS pool_log_abandoned,
    50 AS pool_abandon_when_percentage_full,
    1 AS pool_test_on_borrow,
    40 AS pool_max_active,
    20 AS pool_min_idle,
    10 AS pool_max_idle,
    60 AS pool_suspect_timeout,
    34000 AS pool_time_between_eviction_runs_millis,
    60000 AS pool_min_evictable_idle_time_millis,
    0 AS deadlock_max_retries,
    1 AS deadlock_max_retry_interval,
    NULL::TEXT AS schema_connection_parameters,
    NULL::VARCHAR(100) AS readonly_schema_server,
    NULL::VARCHAR(100) AS readonly_schema_name,
    NULL::VARCHAR(10) AS readonly_schema_server_port,
    NULL::VARCHAR(100) AS readonly_schema_username,
    NULL::VARCHAR(255) AS readonly_schema_password,
    NULL::TEXT AS readonly_schema_connection_parameters,
    NULL::VARCHAR(255) AS master_password_hash
FROM tenant t
WHERE t.is_active = true;
```

#### View: `tenants`

One row per tenant; `oltp_id` and `report_id` both set to tenant id so they join to the single connection row above.

```sql
-- View compatible with Fineract's tenants (read-only use by Fineract)
CREATE OR REPLACE VIEW tenants AS
SELECT
    t.id AS id,
    COALESCE(NULLIF(TRIM(t.tenant_id), ''), t.tenant_key) AS identifier,
    COALESCE(t.name, t.tenant_key) AS name,
    'UTC' AS timezone_id,
    NULL::INT AS country_id,
    NULL::DATE AS joined_date,
    t.created_date AS created_date,
    t.last_modified_date AS lastmodified_date,
    t.id AS oltp_id,
    t.id AS report_id
FROM tenant t
WHERE t.is_active = true;
```

#### Notes

- **Filter:** Both views restrict to `is_active = true`. Inactive tenants are not visible to Fineract.
- **Timezone:** `timezone_id` is fixed to `'UTC'`. To support per-tenant timezone, add a `timezone_id` (or similar) column to the tenant-service `tenant` table and use it here.
- **Read-only / master_password_hash:** Set to NULL; Fineract can still resolve tenants and build JDBC URLs. If you later use Fineract’s encryption for tenant passwords, you would need a different approach (e.g. sync hashes into tenant-service or keep a small table for hashes).
- **Schema name:** For PostgreSQL, Fineract often uses `schema_name` as the **database name** in the JDBC URL. The view uses `COALESCE(t.database_name, t.schema_name, 'public')` so the connection points at the correct database.
- **Port:** Cast `database_port` to text for `schema_server_port`; default `'5432'` if null.

---

## 5. Fineract Configuration

### 5.1 Point tenant datasource at tenant-service DB

Point Fineract’s **tenant datasource** (`hikariTenantDataSource`) at the **tenant-service database** (same DB as tenant-management-service, or a read-only replica). In Fineract’s `application.properties` the tenant store is typically configured via:

```properties
# Example: use tenant-service DB as tenant store (read-only for Fineract)
spring.datasource.hikari.jdbcUrl=jdbc:postgresql://<host>:<port>/<tenant_service_db>?currentSchema=public
spring.datasource.hikari.username=<user>
spring.datasource.hikari.password=<password>
```

Or via environment variables: `FINERACT_HIKARI_JDBC_URL`, `FINERACT_HIKARI_USERNAME`, `FINERACT_HIKARI_PASSWORD`, and the fineract.tenant.* equivalents for host/port/username/password where used.

### 5.2 Disable Fineract’s tenant-store Liquibase (required)

Because the tenant store is **managed by tenant-management-service** (views + `tenant` table), Fineract must **not** run its own Liquibase changelog on the tenant datasource. Otherwise Fineract would create or alter tables in that database.

Set:

```properties
fineract.tenant.tenant-store-managed-externally=true
```

Or:

```bash
FINERACT_TENANT_STORE_MANAGED_EXTERNALLY=true
```

When this is `true`, `TenantDatabaseUpgradeService` skips **upgradeTenantStore()**: it does not run any Liquibase changesets on the tenant datasource. Fineract still runs Liquibase on **each tenant’s business database** (as before) when `upgradeIndividualTenants()` runs; only the **tenant store** DB is left untouched.

Ensure:

- Fineract **only runs SELECT** against the tenant store for tenant resolution. The DB user can be read-only; using a read-only user is recommended.

---

## 6. Fineract Tenant Database Creation (Same as Other Platforms)

When a **new Fineract tenant** is created via tm.atparui.com, the **tenant’s business database** is created the **same way as for other platforms**:

1. **Tenant record** is created in tenant-management-service (table `tenant`), with platform = Fineract and connection details (database_host, database_port, database_name, database_username, database_password, etc.).
2. **Database provisioning** (if enabled): `DatabaseProvisioningService` creates the PostgreSQL database and user for the tenant.
3. **Liquibase on tenant DB:** `TenantLiquibaseService` runs the **platform’s** Liquibase (from the platform’s `service_github_repo` – e.g. Fineract’s repo) against the **tenant’s** database. That applies Fineract’s **business schema** (tenant changelog, e.g. `tenant/changelog-tenant.xml` and modules) to the new DB. So the Fineract tenant database is created and migrated using the same Liquibase-based flow as for RMS or other platforms.
4. **Keycloak** (and any other provisioning) is performed as configured.

No separate “Fineract tenant store” insert is needed: the new tenant appears in the **views** automatically (because it exists in `tenant` with `is_active = true`), and Fineract resolves it by identifier and connects to the tenant’s business DB.

---

## 7. Deployment / Migration Steps

1. **Views:** Ensure tenant-management-service has run its Liquibase (so views `tenants` and `tenant_server_connections` exist). If you added the changelog later, run the service once or apply the script in `docs/FINERACT_TENANT_VIEWS.sql` manually.
2. **Configure Fineract** to use the tenant-service DB for the tenant datasource and set `fineract.tenant.tenant-store-managed-externally=true` (§5).
3. **Verify:** Restart Fineract and call an API with a valid tenant identifier; Fineract should resolve the tenant from the views and connect to the tenant’s business DB.
4. **Decommission:** Once stable, stop using the old fineract-tenants schema; all tenant lookups are served from tenant-service via the views.
5. **Tenant creation:** Create new tenants only via tm.atparui.com (create/provision). New tenants appear in the views automatically (when `is_active = true`); their business DB is created and migrated by TM’s Liquibase flow (§6).

---

## 8. Optional: Timezone and Read-Only Replicas

- **Timezone:** Add a column (e.g. `timezone_id` or `timezone`) to `tenant` in tenant-service and use it in the `tenants` view instead of `'UTC'`.
- **Read-only replica:** If Fineract supports a separate read-only connection for reporting, the view can expose `readonly_*` from tenant-service when available; otherwise leave as NULL.

---

## 9. Summary

| Item | Description |
|------|-------------|
| **Views** | `tenants` and `tenant_server_connections` in tenant-service DB, backed by `tenant` table |
| **Fineract** | Uses same SQL (SELECT) via `TenantMapper`; no code change; only datasource config |
| **Writes** | None from Fineract; tenant creation only via tm.atparui.com |
| **Old schema** | fineract-tenants can be retired once Fineract points at tenant-service DB and views are in place |

This keeps the platform aligned on a single tenant store and simplifies maintenance while preserving Fineract’s existing tenant resolution logic.
