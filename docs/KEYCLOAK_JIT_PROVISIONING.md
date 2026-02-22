# Keycloak JIT User Provisioning (Fineract)

When Keycloak is enabled, Fineract can **provision an AppUser on first login** if the user does not exist for the current tenant (just-in-time, JIT). This aligns with [Keycloak as single source for users](KEYCLOAK_SINGLE_SOURCE_USER_DESIGN.md).

## Behaviour

1. Request arrives with valid Keycloak JWT and tenant (header or JWT claim).
2. `KeycloakJwtAuthenticationTokenConverter` resolves username from `preferred_username` (or `sub`) and calls `TenantAwareJpaPlatformUserDetailsService.loadUserByUsername(username)`.
3. If the user **exists** → build token and continue.
4. If **UsernameNotFoundException** is thrown and **JIT is enabled**:
   - `KeycloakJitUserProvisioningService.provisionUserIfAbsent(username, jwt)` creates an AppUser:
     - **Username** = JWT `preferred_username` (or `sub`)
     - **Office** = `fineract.security.keycloak.jit.default-office-id` or first office by id
     - **Role** = role named `fineract.security.keycloak.jit.default-role-name` (e.g. "Self Service User")
     - **Email / first / last name** = from JWT claims `email`, `given_name`, `family_name` (or `name`) if present
     - **Password** = random (auth is via Keycloak only); no email sent
   - Converter then retries `loadUserByUsername(username)` and continues.
5. If JIT is disabled or provisioning fails (e.g. no office/role), the request returns **401 INVALID_TOKEN**.

## Configuration

Only applies when `fineract.security.keycloak.enabled=true`.

```yaml
fineract:
  security:
    keycloak:
      enabled: true
      issuer-uri: https://your-keycloak/realms/your-realm
      jit:
        enabled: true
        default-role-name: "Self Service User"
        default-office-id: 1   # optional; if unset, first office (by id) is used
```

- **jit.enabled** (default: `true`) – turn JIT on/off.
- **jit.default-role-name** (default: `"Self Service User"`) – Fineract role name for new users. The role must exist in the tenant.
- **jit.default-office-id** (optional) – Office id for new users. If not set, the first office (by id) for the tenant is used.

## Components

- **KeycloakJitUserProvisioningService** (`useradministration.service`) – Conditional on Keycloak enabled; creates AppUser via `UserDomainService.create(..., false)` (no email).
- **KeycloakJwtAuthenticationTokenConverter** – On `UsernameNotFoundException`, calls JIT service then retries `loadUserByUsername`.

## Roles after JIT

JIT assigns a **single default role**. Admins can change or add Fineract roles via the Fineract UI/API. Optional future: map Keycloak realm roles to Fineract roles at JIT.
