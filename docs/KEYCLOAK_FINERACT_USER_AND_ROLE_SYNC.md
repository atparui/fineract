# Keycloak and Fineract: User and Role Sync

This document explains how **Keycloak users** and **Fineract users** are linked, how **roles** are used, and what you need to configure in Keycloak and Fineract. There is **no automatic sync**; identity and permissions are aligned by configuration and data.

---

## 1. How user identity is resolved (no automatic sync)

### Flow in code

1. **Finos web app** (or any client) sends the Keycloak **access token** (JWT) in the `Authorization: Bearer <token>` header and the **tenant** in the `Fineract-Platform-TenantId` header (or in the JWT claim `tenant`).
2. **Fineract** validates the JWT with Keycloak’s issuer (e.g. `https://auth.atparui.com/realms/nbk-demo`) and then uses `KeycloakJwtAuthenticationTokenConverter` to build the security context.
3. The converter:
   - Reads **username** from the JWT: **`preferred_username`** if present, otherwise **`sub`**.
   - Calls **`TenantAwareJpaPlatformUserDetailsService.loadUserByUsername(username)`** for the **current tenant** (from header or JWT).
   - That service loads the user from **Fineract’s `m_appuser`** table: `findByUsernameAndDeletedAndEnabled(username, false, true)` for that tenant.
4. If **no AppUser** exists for that username in that tenant, Fineract throws **`UsernameNotFoundException`** → **INVALID_TOKEN** (401).

So: **Keycloak does not create or update Fineract users.** For every Keycloak user who should call the Fineract API, you must have a matching **Fineract AppUser** with the **same username** (and for the correct tenant).

### What you must do (user “sync”)

| Where | What to do |
|-------|------------|
| **Keycloak** | Create users as usual. Ensure **Username** (or the attribute that becomes `preferred_username` in the JWT) is set to the value you want to use in Fineract (e.g. `maria`, `admin`). |
| **Fineract** | For each Keycloak user who should access Fineract, create an **AppUser** (via Fineract Admin UI or API) with **username** = Keycloak’s **preferred_username** (and assign office, roles, etc.). Use the **same tenant** that the client sends in `Fineract-Platform-TenantId` (e.g. `default`). |

So “sync” is **by hand or by your own process**: same username in Keycloak (as `preferred_username`) and in Fineract (`m_appuser.username`), per tenant.

---

## 2. How roles and permissions work (no automatic role sync)

### Two sources of authorities in Fineract

`KeycloakJwtAuthenticationTokenConverter` **merges** three sets of authorities:

1. **JWT scopes** (e.g. `openid`, `profile`) → converted to `GrantedAuthority` by Spring’s `JwtGrantedAuthoritiesConverter`.
2. **Keycloak realm roles** from the JWT claim **`realm_access.roles`** → each role string is turned into **`ROLE_<role>`** (e.g. `superuser` → `ROLE_superuser`).
3. **Fineract user’s authorities** → from **AppUser.getAuthorities()**, which are **permission codes** (e.g. `READ_USER`, `CREATE_LOAN`) coming from the user’s **Fineract roles** (`m_appuser_role` → `m_role` → `m_role_permission` → `m_permission`).

So:

- **Keycloak realm roles** are added as **ROLE_*** authorities. They are **optional** for normal Fineract API access (see below).
- **Fineract’s own roles/permissions** are what actually control access to Fineract APIs (permission-based checks).

### How Fineract authorizes API access

- Fineract’s **authorization is permission-based**: it checks **permission codes** (e.g. `READ_USER`, `CREATE_LOAN`) that come from **Fineract’s** `m_role` and `m_permission` tables, **not** from Keycloak role names.
- Those permissions are loaded when the **AppUser** is loaded (AppUser → roles → permissions → codes). So the **Fineract roles** you assign to the **AppUser** in Fineract define what the user can do in the API.
- The only explicit **authority** used in the security config is **`TWOFACTOR_AUTHENTICATED`** (for 2FA). There are no API endpoints that require a specific **Keycloak** realm role name for normal access.

So: **there is no automatic role sync.** You do **not** need to mirror Fineract role names in Keycloak for basic API access. You **do** need to assign the right **Fineract roles** to each **AppUser** in Fineract.

### What you must do (roles / permissions)

| Where | What to do |
|-------|------------|
| **Fineract** | For each AppUser (username = Keycloak `preferred_username`), assign the right **Fineract roles** (e.g. “Super user”, “Loan officer”). Those roles already have permissions (e.g. READ_USER, CREATE_LOAN) in `m_role_permission` / `m_permission`. That is what controls API access. |
| **Keycloak (realm roles)** | **Optional.** You only need Keycloak realm roles if you add custom logic that checks for `ROLE_*` (e.g. `hasRole("superuser")`). For the current Fineract codebase, API access is driven by **Fineract permissions**, not Keycloak role names. You can still define realm roles in Keycloak for SSO/UI or future use (e.g. “fineract-admin”, “fineract-user”) and assign them to users; they will appear as `ROLE_*` in the token and in the merged authorities. |

---

## 3. Tenant

- Tenant is taken from (in order): JWT claim **`tenant`**, request parameter **`tenantId`**, or header **`Fineract-Platform-TenantId`**.
- The **AppUser** is looked up **for that tenant**. So the same Keycloak user (e.g. `maria`) can map to different Fineract users in different tenants if you create separate AppUsers per tenant with the same username.

---

## 4. Summary: what to configure

### In Keycloak (realm `nbk-demo`)

- **Users**: Create users and set **Username** (or the attribute that becomes `preferred_username`) to the value you will use as the Fineract username (e.g. `maria`, `admin`).
- **Realm roles** (optional for Fineract API access):
  - You can create roles like `fineract-admin`, `fineract-user` for organization or future use.
  - They are **not required** for the current Fineract permission checks; Fineract relies on **Fineract roles** on the AppUser.

### In Fineract

- **Per tenant**: For each Keycloak user who should access the API, create an **AppUser** with:
  - **username** = Keycloak’s **preferred_username** (must match exactly).
  - **Office**, **Fineract roles** (e.g. “Super user”), and any other required fields.
- **Fineract roles** on that AppUser define the user’s **permissions** (e.g. READ_USER, CREATE_LOAN). No sync from Keycloak; you assign Fineract roles in Fineract.

### Sync process (manual or automated)

- **User “sync”**: Keep **username** in Keycloak (`preferred_username`) and in Fineract (`m_appuser.username`) the same; create/update Fineract users by hand or via script/API when you create/change Keycloak users.
- **Role “sync”**: There is no automatic role sync. Configure **Fineract roles** on each AppUser in Fineract. Keycloak realm roles are optional and additive (they become `ROLE_*` in the token).

---

## 5. Code references (Fineract)

| File | What it does |
|------|------------------|
| `KeycloakJwtAuthenticationTokenConverter` | Gets username from JWT `preferred_username` (or `sub`); loads Fineract user by that username; merges JWT scopes, **realm_access.roles** (as ROLE_*), and Fineract user’s permissions into the security context. |
| `TenantAwareJpaPlatformUserDetailsService` | Loads **AppUser** from `m_appuser` by username (and tenant from `ThreadLocalContextUtil`). |
| `TenantAwareAuthenticationFilter` | Sets tenant from JWT claim `tenant`, request param `tenantId`, or header `Fineract-Platform-TenantId`. |
| `AppUser.getAuthorities()` | Returns **permission codes** from the user’s Fineract roles (m_role → m_permission), e.g. `READ_USER`, `CREATE_LOAN`. |

This is how Keycloak and Fineract users and roles work today and how to align them without automatic sync.

---

**Recommended approach (Keycloak as single source, JIT provisioning, role sync):** See **KEYCLOAK_SINGLE_SOURCE_USER_DESIGN.md** for a concise design where Keycloak is the only place to add/delete users, and each platform (Fineract, RMS) provisions users on first login and uses optional Keycloak→app role mapping to keep roles in sync.
