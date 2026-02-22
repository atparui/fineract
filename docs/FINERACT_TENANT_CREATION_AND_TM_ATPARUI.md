# Fineract Tenant Creation and tm.atparui.com

This document describes (1) how a new tenant is created in Fineract, (2) how the tenant-management-service (tm.atparui.com) creates tenants and databases, and (3) how to align tenant creation so it is done via the Tenant Manager web app (tm.atparui.com).

---

## 1. How Fineract Stores and Resolves Tenants

Fineract is multi-tenant. It uses **two levels** of data:

### 1.1 Tenant store (Fineract’s own DB)

- A **separate database** (the “tenant store”) holds **which tenants exist** and **how to connect** to each tenant’s business data.
- It is configured via `fineract.tenant.*` in `application.properties` (or env vars like `FINERACT_DEFAULT_TENANTDB_*`). This is the datasource behind `hikariTenantDataSource`.
- **Tables:**
  - **`tenants`** – one row per tenant: `id`, `identifier` (tenant id, e.g. `default`), `name`, `timezone_id`, `oltp_id`, `report_id`.  
    `oltp_id` and `report_id` point to `tenant_server_connections.id`.
  - **`tenant_server_connections`** – connection details: `schema_server`, `schema_server_port`, `schema_name`, `schema_username`, `schema_password`, pool settings, optional read-only fields, etc.
- At runtime, when a request carries a tenant id (e.g. header `Fineract-Platform-TenantId` or JWT claim), Fineract calls **`AuthTenantDetailsService.loadTenantById(tenantId)`**, which runs a SQL query on this tenant store and returns connection details. The business data for that tenant lives in the **database/schema** described by that connection (MySQL/PostgreSQL).

### 1.2 Tenant business database

- Each tenant has its **own database** (or schema) containing all Fineract business tables (`m_office`, `m_appuser`, `m_client`, etc.).
- That database must already exist and must have been **initialized with Fineract’s schema** (e.g. via Liquibase/Flyway or manual migration).

So **creating a new tenant in Fineract** means:

1. **Create the tenant’s business database** (or schema) and run Fineract’s schema migrations on it.
2. **Insert one row** into `tenant_server_connections` with the connection details for that database.
3. **Insert one row** into `tenants` with `identifier`, `name`, `timezone_id`, and `oltp_id` / `report_id` pointing to the new connection row.

Fineract does **not** expose a REST API for “create tenant”. Tenants are usually added by:

- Bootstrap/migration scripts, or
- Manual SQL against the tenant store, or
- An external system that has access to both the tenant store and the ability to create and migrate the tenant DB.

---

## 2. How the Tenant-Management Service (tm.atparui.com) Creates Tenants

The **tenant-management-service** (and **tenant-manager-web-app** at tm.atparui.com) is a **separate** system. It:

- Stores tenants in its **own** `tenant` table (and related tables: platform, database vendor, tenant_clients, etc.).
- Is used by the **Console gateway** (and other apps) for **Keycloak realm resolution** and tenant metadata.
- Does **not** write into Fineract’s `tenants` or `tenant_server_connections` tables.

### 2.1 Tenant creation endpoints

| Endpoint | Purpose |
|----------|--------|
| **POST /api/tenants** | Create a tenant record. Optional `applyLiquibase=true` runs Liquibase for that tenant using the **platform’s** `serviceGithubRepo`. |
| **POST /api/tenants/provision** | **Full provisioning**: DNS (optional), tenant record, **create PostgreSQL DB** (via `DatabaseProvisioningService`), run **Liquibase** from the platform’s service repo, create **Keycloak realm and clients**, optionally fork GitHub repos. |

### 2.2 What “create tenant” does in TM

- **POST /api/tenants** (and the **Create Tenant** form in the web app):
  - Validates and saves a **Tenant** entity (tenantKey, name, platformId, databaseUrl, databaseUsername, databasePassword, etc.).
  - If the tenant has `databaseProvisioningMode = AUTO_CREATE`, TM will **create a new PostgreSQL database** (and user) via `DatabaseProvisioningService` and fill in `databaseUrl`, `databaseUsername`, `databasePassword` on the tenant.
  - Optionally creates a **Keycloak realm** (`KeycloakRealmService`).
  - If `applyLiquibase=true`, runs **Liquibase** using the **platform’s** `service_github_repo` (clone + run `master.xml`). So the **schema** that is created is the **platform’s** schema (e.g. RMS/Console), **not** Fineract’s schema, unless the platform is configured to use a repo that contains Fineract’s migrations.

- **POST /api/tenants/provision**:
  - Same idea, but in one shot: DNS (optional), tenant record, PostgreSQL DB creation, Liquibase from platform repo, Keycloak realm/clients, optional GitHub fork.
  - Database created is **PostgreSQL**. Name pattern: `{platformPrefix}_{tenantKey}` (e.g. `rms_demo`).

So: **tenant creation in TM = create record in TM DB + (optionally) create a PostgreSQL DB + run platform Liquibase + Keycloak**. It does **not** by itself create a row in Fineract’s `tenants` or `tenant_server_connections`.

---

## 3. Making Tenant Creation Happen “via tm.atparui.com”

You want **one place** to create tenants (tm.atparui.com) and have Fineract use them. That requires connecting the two worlds.

### 3.1 Current state

- **Tenant Manager (tm.atparui.com):**  
  - Web app calls `tenantApi.create()` → **POST /api/tenants** (or you could call **POST /api/tenants/provision** from the app).  
  - Creates tenant in TM DB and optionally a **PostgreSQL** DB + Liquibase from a **platform** repo.
- **Fineract:**  
  - Resolves tenants from its **own** tenant store (`tenants` + `tenant_server_connections`).  
  - Does not read from TM API.

So today, **creating a tenant in TM does not automatically create a tenant in Fineract**.

### 3.2 Options to align with “tm.atparui.com way”

**Option A – Fineract as a platform in TM (recommended for single flow)**

1. Add a **Platform** in TM for Fineract (e.g. name “Fineract”, prefix “FIN” or “fineract”).
2. Set the platform’s **service_github_repo** to a repository that contains **Fineract’s business-DB Liquibase changelogs** (the ones that create `m_office`, `m_appuser`, etc.), so that when TM runs Liquibase for a new tenant it creates a **Fineract schema** in the new PostgreSQL DB.
3. When creating a tenant in the **Tenant Manager**:
   - Select the **Fineract** platform.
   - Use **Full provision** (POST /api/tenants/provision) so TM creates the PostgreSQL DB and runs Fineract migrations.
4. **Sync tenant into Fineract’s tenant store:**
   - After TM has created the tenant and DB, either:
     - **A1** Run a **script or small service** that (for each new Fineract tenant in TM) inserts the corresponding row(s) into Fineract’s `tenant_server_connections` and `tenants` (using the same `identifier` as TM’s `tenantId` / `tenantKey`, and the same DB connection details), or
     - **A2** Implement an **AuthTenantDetailsService** in Fineract that **calls the TM API** (e.g. `GET /api/tenants/{tenantId}/database-config`) and maps the response to `FineractPlatformTenant` / connection, so Fineract **resolves tenants from tm.atparui.com** instead of from its own DB.

**Option B – Keep two steps (TM + manual Fineract registration)**

1. Create the tenant in tm.atparui.com (Create Tenant or Provision).
2. Manually (or via a separate script) create the Fineract tenant:
   - Create the Fineract business DB (or use the one TM created if it already ran Fineract migrations).
   - Insert into Fineract’s `tenant_server_connections` and `tenants` with the same `identifier` as in TM.

**Option C – Fineract reads from TM API**

- Implement a **Fineract AuthTenantDetailsService** that loads tenant by id from **tenant-management-service** (e.g. `GET https://tm.atparui.com/services/tenant-management-service/api/tenants/{tenantId}/database-config`).
- Map TM’s `TenantDatabaseConfigDTO` (databaseUrl, username, password, etc.) to Fineract’s `FineractPlatformTenant` / connection.
- Then **any** tenant created in TM (and exposed by that API) is visible to Fineract without writing to Fineract’s tenant store.  
- Prerequisite: TM’s tenant must point to a DB that has **Fineract’s schema** (e.g. by having a “Fineract” platform and using that when provisioning).

**Option D – Single tenant store: Fineract reads from tenant-service DB via views**

- Use **tenant-service** as the only tenant store. In the tenant-service database, create **views** named `tenants` and `tenant_server_connections` that expose the existing `tenant` table in the shape Fineract expects.
- Point Fineract’s **tenant datasource** (`hikariTenantDataSource`) at the **tenant-service database**. Fineract continues to run the same SELECT query (no code change); it reads from the views instead of tables.
- Tenant **creation** happens only via tm.atparui.com; Fineract never writes to the tenant store. The old fineract-tenants schema can be retired.
- See **[FINERACT_TENANT_VIEWS_TENANT_SERVICE.md](./FINERACT_TENANT_VIEWS_TENANT_SERVICE.md)** for view DDL, mapping, and configuration.

**Option E – Fineract uses tenant-management-service API only (full isolation)**

- Fineract **does not connect to any tenant store database**. It resolves tenants by calling the **tenant-management-service API** (e.g. `GET /api/tenants/{tenantId}/database-config`). No tenant DB URL or credentials in Fineract.
- TM remains the single source of truth; TM can add Fineract-specific response fields or endpoints so its “internal logic (is) adjusted to suit the requirement of the fineract”.
- Best isolation: tenant connection details live only in TM; Fineract only holds TM base URL and optional auth.
- See **[FINERACT_TENANT_RESOLUTION_VIA_TENANT_SERVICE_API.md](./FINERACT_TENANT_RESOLUTION_VIA_TENANT_SERVICE_API.md)** for design, Fineract/TM changes, and comparison with the view-based approach.

---

## 4. Procedure: Create a New Tenant via tm.atparui.com (target flow)

Goal: **All new tenants are created from the Tenant Manager web app** (tm.atparui.com), and Fineract uses them.

### 4.1 Prerequisites

- A **Platform** in TM that represents Fineract (prefix, `service_github_repo` = Fineract business-DB migrations).
- TM and Fineract agree on **tenant identifier** (e.g. TM `tenantId` / `tenantKey` = Fineract `tenants.identifier`).
- Either:
  - **Sync path:** After TM creates a tenant, a process (script or TM-side call) inserts/updates Fineract’s `tenants` and `tenant_server_connections`, or
  - **Fineract reads from TM:** Fineract’s `AuthTenantDetailsService` is implemented to call TM’s database-config API (see Option C above).

### 4.2 Steps in the Tenant Manager web app

1. Log in to **tm.atparui.com** (with a user that has `ROLE_ADMIN`).
2. Go to **Tenants** and click **Create Tenant** (or use a “Full provision” flow if the UI supports it).
3. Fill in:
   - **Tenant key** (e.g. `demo` or `org1`) – will be used as tenant id in APIs and, if synced, as Fineract `identifier`.
   - **Name**, **Platform** (e.g. Fineract), **subdomain** if needed.
   - For **full automation**: use provisioning mode that creates DB and runs Liquibase (or call **POST /api/tenants/provision** with the same payload).
4. Submit. TM will:
   - Create the tenant record.
   - If provisioning: create PostgreSQL DB, run Liquibase from the platform repo (Fineract schema if platform is Fineract), create Keycloak realm/clients.

### 4.3 Steps so Fineract knows the tenant

- **If Fineract reads from TM API:**  
  No extra step; Fineract will resolve the new tenant by id when requests use that tenant id (e.g. `Fineract-Platform-TenantId: demo`).

- **If you use a sync or manual registration:**  
  Ensure Fineract’s tenant store has a row for that tenant:
  - Insert into `tenant_server_connections` (connection to the tenant’s DB).
  - Insert into `tenants` with `identifier` = same as TM tenant id, and `oltp_id` / `report_id` pointing to the new connection.

### 4.4 Database creation summary

| System | Who creates the DB? | How |
|--------|----------------------|-----|
| **TM** | `DatabaseProvisioningService` | Creates PostgreSQL DB and user; name like `{platformPrefix}_{tenantKey}`. |
| **Fineract** | Not by default | Fineract only **connects** to DBs listed in `tenant_server_connections`; it does not create them. Schema is usually applied by Liquibase (e.g. run by TM from platform repo or run separately). |

So in the “tm.atparui.com way”, **database creation** is done by the **tenant-management-service** during create/provision; **Fineract** only needs to be given the connection (via its tenant store or via TM API).

---

## 5. Tenant Manager web app – current behaviour

- **Create Tenant** form (`/tenants/create`) uses **tenantApi.create()** → **POST /api/tenants** (no `applyLiquibase` in the current form; body is built from `TenantCreate`).
- The backend expects a **platformId** and other fields for full provisioning; the create form may need to include **platform** and, for full automation, a way to call **POST /api/tenants/provision** (e.g. “Provision tenant” button) so that DB creation + Liquibase + Keycloak all run from the UI.

To have **tenant creation fully via tm.atparui.com** including DB and Fineract schema:

1. Ensure the create/provision API is called with the correct **platform** (Fineract) and that the platform has the correct **service_github_repo** for Fineract migrations.
2. Either add a **“Full provision”** action in the UI that calls **POST /api/tenants/provision**, or extend the create flow so that new tenants are provisioned (DB + Liquibase) from the same screen.
3. Implement one of the Fineract-side options above (sync to Fineract tenant store, or Fineract loading tenant from TM API) so that Fineract recognizes tenants created in TM.

---

## 6. References

- Fineract tenant store: `fineract-provider/src/main/resources/db/changelog/tenant-store/` (Liquibase), `TenantMapper.java`, `AuthTenantDetailsServiceJdbc.java`.
- **Single tenant store (views):** [FINERACT_TENANT_VIEWS_TENANT_SERVICE.md](./FINERACT_TENANT_VIEWS_TENANT_SERVICE.md) – use tenant-service DB as Fineract tenant store via views.
- TM tenant creation: `tenant-management-service` → `TenantResource.java`, `TenantService.java`, `DatabaseProvisioningService.java`, `TenantLiquibaseService.java`.
- TM web app: `tenant-manager-web-app` → `lib/api-client.ts` (base URL e.g. `https://tm.atparui.com`), `app/(dashboard)/tenants/create/page.tsx`.
