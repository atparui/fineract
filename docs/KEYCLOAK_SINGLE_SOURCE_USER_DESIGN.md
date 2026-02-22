# Keycloak as Single Source for Users – Recommended Approach

**Goal:** Keycloak is the only place to register/add/delete users. Each platform (Fineract, RMS, etc.) provisions users on first access and is used only for **assigning app-specific roles and profiles**. Keycloak users and roles stay in sync with platform users and roles via a **standard, fast-to-implement** pattern.

---

## 1. Recommended model (concise)

| Responsibility | Where | What |
|----------------|--------|------|
| **User lifecycle** (create, delete, enable/disable) | **Keycloak only** | Create/delete users in Keycloak Admin (or your IdP UI). Assign **realm roles** (and client roles) here. |
| **User record in each app** | **JIT provisioning** (in each app) | On **first successful login** (valid JWT), if the user does not exist in the app, **create** them with a **default role**. Optionally update profile (name, email) from JWT on each login. |
| **App-specific roles and profile** | **In each platform** | After JIT has created the user, admins assign **Fineract roles** (Fineract UI/API), **RMS branch roles** (RMS UI), etc. Optional: **map Keycloak realm roles → app roles** at JIT so initial role sync is automatic. |

So: **Keycloak = single source for identity and (optionally) high-level roles.** Apps = **JIT create user + default role**, then **app UI/API for fine-grained roles and profile**. No separate “sync service” required for fast implementation.

---

## 2. Standard pattern: JIT provisioning on first auth

- When a request arrives with a **valid Keycloak JWT**:
  1. App validates the JWT and resolves **username** (e.g. `preferred_username`) and **tenant**.
  2. App looks up **local user** by external id or username (and tenant).
  3. **If not found** → **create** user with:
     - Identity from JWT (username, email, first/last name, etc.).
     - A **default role** (e.g. “Self Service User” in Fineract, “User” in RMS).
     - Optional: assign more roles from a **Keycloak role → app role** mapping (see below).
  4. **If found** → optionally **update** profile from JWT (name, email) and continue.

This is **just-in-time (JIT) provisioning**. It is a common, standard approach and avoids a separate sync job or sync service.

---

## 3. Per-platform status and next steps

### RMS (rms-service)

- **Already implemented.** `UserProvisioningAuthSuccessListener` runs on `AuthenticationSuccessEvent`, creates/updates `rms_user` from JWT (`sub`, `preferred_username`, email, name, etc.) and logs to `user_sync_log`.
- **Roles:** Assign in RMS (e.g. `user_branch_role`, app-specific roles). Optional later: map Keycloak realm roles to RMS roles when creating/updating the user.

### Fineract

- **Implemented.** JIT provisioning runs when Keycloak is enabled (see KEYCLOAK_FINERACT_USER_AND_ROLE_SYNC.md for config). Missing users are created on first login with default office and role.
- **To align with the model:** Add **JIT provisioning** when Keycloak is enabled:
  1. In the auth flow (e.g. in or after `KeycloakJwtAuthenticationTokenConverter`), when `loadUserByUsername(username)` throws `UsernameNotFoundException`, **create** an AppUser instead of failing:
     - Username = `preferred_username`, office = default office (configurable), one **default role** (e.g. “Self Service User” or a minimal role by name/ID from config).
     - Password can be a random/placeholder (user authenticates via Keycloak only).
  2. Then retry `loadUserByUsername(username)` so the rest of the flow is unchanged.
- **Roles:** Admins assign Fineract roles in Fineract UI/API. Optional: configurable mapping (Keycloak realm role → Fineract role name) applied at JIT so Keycloak roles stay in sync with Fineract roles for new users.

### Other platforms

- Apply the same pattern: **JIT on first auth** (create user + default role), then use the app for roles and profile. Optionally map Keycloak roles to app roles at JIT.

---

## 4. Keeping Keycloak roles in sync with platform roles (optional)

Two levels:

**A) No mapping (fastest)**  
- JIT creates user with **one default role** per app.  
- Admins assign real roles in each app (Fineract, RMS).  
- Keycloak roles used for SSO/display only; no automatic sync to app roles.

**B) Role mapping at JIT (recommended for “sync”)**  
- Configuration per app, e.g.:
  - Keycloak realm role `fineract-superuser` → Fineract role “Super user”
  - Keycloak realm role `fineract-loan-officer` → Fineract role “Loan officer”
  - Keycloak realm role `rms-manager` → RMS role “Manager”
- When **creating** the user in the app (JIT), read `realm_access.roles` from the JWT and assign the corresponding app roles. When **updating** (user already exists), optionally add/remove app roles based on current Keycloak roles so that Keycloak remains the source of truth for “which roles this user has” and the app stays in sync.

Implement **A** first (JIT + default role); add **B** when you need automatic role sync from Keycloak.

---

## 5. User delete / disable

- **Keycloak:** Delete or disable the user in Keycloak Admin. They can no longer get a valid token.
- **Platforms:** Either:
  - **Manual:** Disable/delete the user in each app when you remove them from Keycloak, or
  - **Event-driven (later):** Keycloak Event Listener or webhook calls each platform’s API to disable/delete the user when Keycloak fires “user deleted” or “user disabled”.

For fastest implementation, start with manual; add event-driven disable/delete when needed.

---

## 6. Summary: what to implement (in order)

1. **Keycloak**  
   - Single place to create/delete users and assign realm (and client) roles.

2. **Fineract – JIT provisioning**  
   - When Keycloak is enabled and `loadUserByUsername` throws (user not in `m_appuser`), create AppUser with username from JWT, default office, and one default role (e.g. “Self Service User”), then retry load.  
   - No separate sync service; implement inside existing auth flow.

3. **RMS**  
   - Already JIT; no change required. Optionally add Keycloak role → RMS role mapping later.

4. **Optional: Keycloak → app role mapping**  
   - At JIT (and optionally on each login), map `realm_access.roles` to app roles so Keycloak roles and platform roles stay in sync.

5. **Optional: Keycloak events for delete/disable**  
   - When you need automatic deactivation in apps, add a Keycloak listener or webhook that calls Fineract/RMS APIs to disable or delete the user.

This gives you a **concise, standard** setup: Keycloak as single source, apps used for roles and profile, with sync achieved by JIT plus optional role mapping and optional event-driven disable/delete.
