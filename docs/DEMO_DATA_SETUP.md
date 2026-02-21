# Demo Data Setup for Fineract and Finos Web App

This guide explains how to get **dummy/demo data** (users, customers, savings, loans, etc.) for a proper demo with **Apache Fineract** (backend) and **Finos Web App** (frontend).

---

## What Exists in the Repositories

### Fineract (backend)

1. **Initial data (Liquibase)**  
   On first startup, Liquibase applies `fineract-provider/src/main/resources/db/changelog/tenant/parts/0002_initial_data.xml`, which creates:
   - **1 office:** "Head Office"
   - **3 users:** `mifos` / `password`, `system`, `interopUser` (all with full access)
   - Configuration, permissions, roles, chart of accounts (GL), currencies, and other reference data
   - **No** clients, groups, loan products, savings products, loan accounts, or savings accounts

2. **Demo backup SQL files** (`fineract-db/multi-tenant-demo-backups/`)
   - **bare-bones-demo** (`bk_bare_bones_demo.sql`): Same idea as above — one office, user `mifos`/`password`, no portfolio data.
   - **default-demo** (`bk_mifostenant-default.sql`): Custom chart of accounts; **no** client/loan/savings rows.
   - **latam-demo**: User `quipo`/`quipo`, "Latam HO" office; no portfolio data.
   - **ceda**: Custom COA and schema; not a full preloaded demo.

3. **Sample SQL** (`fineract-provider/.../sql/migrations/sample_data/`)  
   `load_sample_data.sql` is a large reference dump (schema + GL, etc.). It is **not** run automatically by Docker or the application. The repo does **not** include a ready-to-use SQL file that inserts clients, loans, and savings.

### Finos Web App (frontend)

- **Environments** (`src/environments/environment.ts` / `environment.prod.ts`) default to:
  - **Base API URLs:** `https://sandbox.mifos.community`, `https://demo.mifos.community`, `https://localhost:8443`
  - **Tenant:** `default`
- The app does **not** contain dummy data; it expects the **backend** to have data. For a proper demo you either use a hosted backend that already has data or create data via UI/API after connecting to your own Fineract.

---

## Option 1: Use a Hosted Demo (fastest)

Connect Finos Web App to a server that already has demo data.

| Server | Typical credentials | Notes |
|--------|---------------------|--------|
| **sandbox.mifos.community** | `mifos` / `password`, tenant: `default` | Community sandbox |
| **demo.mifos.community** | Same as above | Community demo |
| **fineract.dev** | `mifos` / `password`, tenant: `default` | Resets periodically; latest dev code |

**Steps:**

1. Run Finos Web App (e.g. `npm start` or your usual command).
2. On the login screen, choose the server (e.g. sandbox.mifos.community or demo.mifos.community) if the app allows server switch.
3. Log in with `mifos` / `password`, tenant `default`.

You can then use the UI to explore existing clients, loans, savings, etc., if the instance is preloaded.

---

## Option 2: Local Fineract + Create Data via UI

Get a “proper demo” on your machine by creating everything in the UI.

1. **Start Fineract** (and MariaDB) e.g.:
   - From fineract root: `docker-compose up -d` (or use your normal run configuration).
2. **Wait for first startup** so Liquibase creates the tenant schema and initial data (Head Office, user `mifos`/`password`).
3. **Start Finos Web App** and point it to `https://localhost:8443` (and tenant `default`).
4. **Log in** as `mifos` / `password`.
5. **Create demo data in this order:**
   - **Loan product** (e.g. “Demo Loan”) and **Savings product** (e.g. “Demo Savings”) under the right menus.
   - **Staff** (optional but useful for loan officer).
   - **Clients** (several dummy clients).
   - **Loan accounts** and **Savings accounts** for those clients; disburse loans and make a few transactions.

This gives you full control and a “proper” demo with users, customers, savings, and loans.

---

## Option 3: Restore a Full Backup (if you have one)

If you have a **full** tenant dump that already contains `m_client`, `m_loan`, `m_savings_account`, etc.:

1. Create the tenant and run Fineract once so the schema exists (Liquibase).
2. **Back up** the current `fineract_default` DB if needed.
3. **Restore** your full backup into the `fineract_default` database (or the tenant schema you use).
4. Restart Fineract and connect with Finos Web App.

The dumps under `fineract-db/multi-tenant-demo-backups/` in this repo do **not** include client/loan/savings rows; they are schema + reference data only.

---

## Option 4: Script Data via API

You can create a “proper demo” by calling the Fineract REST API (or the Java SDK) to create:

- Offices (if you need more than Head Office)
- Users (optional)
- Loan products and Savings products
- Charges / fees if needed
- Clients
- Loan accounts (apply, approve, disburse)
- Savings accounts and transactions

**Example (Java SDK)** — from `fineract-client` / docs:

```java
FineractClient fineract = FineractClient.builder()
    .baseURL("https://localhost:8443/fineract-provider/api/v1/")
    .tenant("default")
    .basicAuth("mifos", "password")
    .build();
// Use fineract.clients(), fineract.loans(), etc. to create entities
```

**REST:** Use Swagger (e.g. `https://localhost:8443/fineract-provider/swagger-ui/`) or any HTTP client with Basic Auth and header `Fineract-Platform-TenantId: default` to POST to `/clients`, `/loans`, `/savingsaccounts`, etc.

---

## Summary

| Goal | Recommendation |
|------|-----------------|
| **Quick demo with existing data** | Use **Option 1**: point Finos Web App to sandbox.mifos.community or demo.mifos.community (or fineract.dev) and log in as `mifos`/`password`. |
| **Demo on your own server** | Use **Option 2**: run Fineract + Finos Web App locally, log in as `mifos`/`password`, then create products, clients, loans, and savings via the UI. |
| **Repeatable / automated demo** | Use **Option 4**: script creation via REST API or Java SDK. |
| **Use a pre-made dump** | Use **Option 3** only if you have a full backup that includes portfolio data; the repo’s demo backups do not. |

Default **admin user** after a fresh Fineract install: **`mifos`** / **`password`**, tenant **`default`**.
