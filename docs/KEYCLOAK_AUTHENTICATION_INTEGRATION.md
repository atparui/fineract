# Keycloak Authentication Integration with Fineract

This document describes how to integrate **Keycloak** (e.g. at `auth.atparui.com`) with Apache Fineract so that authentication is handled by Keycloak (SSO) instead of Fineract’s built-in Basic Auth or built-in OAuth2 authorization server.

## References (from web research)

- **Spring Security OAuth2 Resource Server (JWT)**  
  https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html  
  Spring Boot validates JWTs using `issuer-uri` or `jwk-set-uri` and uses Bearer tokens.

- **Spring Boot as Resource Server for Keycloak**  
  https://bootify.io/spring-security/spring-boot-resource-server-for-keycloak.html  
  Uses `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` and a `JwtAuthenticationConverter` to map Keycloak’s `realm_access.roles` to authorities.

- **Keycloak documentation**  
  https://www.keycloak.org/docs/latest/server_admin/  
  For realm and client setup, discovery URL, and JWKS.

- **FintechOS – Authentication with Keycloak**  
  https://docs.fintechos.com/Platform/21.1.7/AdminGuide/Content/Security/authenticationWithKeycloak.htm  
  Conceptual setup: Discovery Endpoint, Client ID, Client Secret, Callback URL.

---

## Current Fineract authentication modes

Fineract supports two mutually exclusive modes:

| Mode | Property | Behaviour |
|------|----------|-----------|
| **Basic Auth** | `fineract.security.basicauth.enabled=true` (default) | Uses `SecurityConfig` and `TenantAwareBasicAuthenticationFilter`. Users send `Authorization: Basic &lt;base64(username:password)&gt;`. Credentials are validated against Fineract’s `m_appuser` (and tenant). |
| **OAuth2 (built-in)** | `fineract.security.oauth2.enabled=true` | Uses `AuthorizationServerConfig`. Fineract acts as its **own** OAuth2 authorization server (login form, token endpoint). It also acts as a **resource server** and validates the JWTs it has issued. JWT subject is mapped to Fineract user via `FineractJwtAuthenticationTokenConverter` and `TenantAwareJpaPlatformUserDetailsService.loadUserByUsername(jwt.getSubject())`. |

To use **Keycloak**, Fineract should **only** validate tokens issued by Keycloak (resource server only). That implies either:

- Adding a **new** “Keycloak resource server” mode (recommended), or  
- Extending the existing OAuth2 path to support an **external JWT issuer** (Keycloak) in addition to the built-in server.

---

## Keycloak endpoints (for auth.atparui.com)

Replace `{realm}` with your Keycloak realm name (e.g. `fineract` or `atparui`).

| Purpose | URL |
|--------|-----|
| **Discovery (OpenID)** | `https://auth.atparui.com/realms/{realm}/.well-known/openid-configuration` |
| **JWKS (public keys for JWT validation)** | `https://auth.atparui.com/realms/{realm}/protocol/openid-connect/certs` |
| **Issuer (used in JWT `iss` claim)** | `https://auth.atparui.com/realms/{realm}` |

Spring Boot can use either:

- **`issuer-uri`** – it will call the discovery URL and use the `jwks_uri` from the response, or  
- **`jwk-set-uri`** – direct URL to the JWKS endpoint.

---

## Integration approach: Fineract as OAuth2 Resource Server for Keycloak

### 1. Keycloak setup (auth.atparui.com)

1. **Realm**  
   Create or choose a realm (e.g. `fineract`).

2. **Client for Fineract API**  
   - Client ID: e.g. `fineract-api` (or a name you use for the app that calls Fineract).  
   - Client authentication: **On** if the client is confidential (e.g. server-side); **Off** for public clients (e.g. SPA).  
   - Valid redirect URIs: e.g. `https://your-fineract-ui/callback` or the URL where users land after login.  
   - Note: For **resource server only** (Fineract validating tokens), you may have a separate “backend” client or use the same client that your frontend uses to get tokens. Fineract only needs to **validate** the JWT; it does not need a client secret unless you add a token introspection flow later.

3. **Discovery endpoint**  
   From Keycloak Admin: Realm → Realm settings → Endpoints → OpenID Endpoint Configuration.  
   Use: `https://auth.atparui.com/realms/{realm}/.well-known/openid-configuration`.

4. **Client ID / Client secret**  
   If your frontend or gateway obtains tokens from Keycloak, use that client’s ID. The **issuer** and **JWKS** URL are realm-level; Fineract only needs the realm issuer/JWKS to validate any token issued by that realm.

### 2. Spring Boot configuration (Fineract as resource server)

Fineract’s current OAuth2 setup is **authorization server + resource server** (it issues and validates its own tokens). For Keycloak you need Fineract to act **only as resource server** and to validate JWTs from Keycloak.

Two ways to configure Spring (once the code path supports external issuer):

**Option A – Issuer URI (recommended)**  
Spring will fetch the OpenID configuration and JWKS from Keycloak:

```properties
# Use Keycloak as the only JWT issuer (external)
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.atparui.com/realms/{realm}
```

**Option B – JWK Set URI only**

```properties
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://auth.atparui.com/realms/{realm}/protocol/openid-connect/certs
```

Important: Keycloak must be **reachable** from the Fineract server at startup (and when validating the first token), so that Spring can load the JWKS.

### 3. JWT authentication converter (Keycloak claims)

Keycloak puts realm roles in `realm_access.roles`. Fineract’s current `FineractJwtAuthenticationTokenConverter` expects the JWT **subject** to be a **Fineract username** (it calls `userDetailsService.loadUserByUsername(jwt.getSubject())`). So either:

- **Username mapping**: Ensure Keycloak users have `username` or `preferred_username` equal to the Fineract user name (and that a matching user exists in Fineract), **or**
- **Keycloak-only converter**: Add a dedicated converter for Keycloak that:
  - Uses `preferred_username` or `sub` as the principal (and optionally maps to Fineract user if you still want DB-backed permissions).
  - Maps `realm_access.roles` (and optionally `resource_access` roles) to `GrantedAuthority` (e.g. `ROLE_&lt;role&gt;`).

Example pattern for mapping Keycloak roles (from Bootify and Spring docs):

```java
// Conceptual: extract realm roles from Keycloak JWT
Map<String, Object> realmAccess = jwt.getClaim("realm_access");
Collection<String> roles = (Collection<String>) realmAccess.getOrDefault("roles", List.of());
// Map to GrantedAuthority, e.g. ROLE_<role>
```

Tenant: Fineract is multi-tenant. Today tenant is often taken from the request (e.g. header or path), not from the JWT. You may need to keep passing tenant in a header and only use Keycloak for identity and optionally roles.

### 4. Suggested implementation steps (code)

1. **New configuration flag**  
   e.g. `fineract.security.keycloak.enabled=true` (or reuse/extend `fineract.security.oauth2` with a “mode” or “external-issuer” option).

2. **Conditional security config**  
   When Keycloak is enabled:
   - Do **not** enable the built-in OAuth2 authorization server (no Fineract login form / token endpoint).
   - Enable only the **OAuth2 resource server** with:
     - `spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.atparui.com/realms/{realm}` (or equivalent from config).

3. **JWT converter**  
   - Either extend `FineractJwtAuthenticationTokenConverter` to support Keycloak (e.g. by checking issuer and then using `preferred_username` + `realm_access.roles`), or  
   - Add a separate `KeycloakJwtAuthenticationTokenConverter` and use it when Keycloak is enabled.

4. **User ↔ Fineract**  
   - If you still need Fineract’s permission model (e.g. from `m_appuser`), keep loading the user by a mapped username from the JWT and optionally sync Keycloak roles to Fineract roles.  
   - If you rely only on Keycloak roles, the converter can build authorities only from the JWT and avoid a DB user lookup (then Fineract permission checks must be aligned with those authorities).

5. **Properties**  
   Add (for example):
   - `fineract.security.keycloak.issuer-uri` or pass through to `spring.security.oauth2.resourceserver.jwt.issuer-uri`
   - Optional: `fineract.security.keycloak.client-id` if you use audience validation.

6. **Disable Basic Auth when Keycloak is on**  
   When `fineract.security.keycloak.enabled=true`, set `fineract.security.basicauth.enabled=false` (or enforce in config) so only Bearer tokens are accepted.

---

## Summary configuration (auth.atparui.com)

| Item | Value |
|------|--------|
| **Keycloak base URL** | `https://auth.atparui.com` |
| **Realm** | Your realm name (e.g. `fineract`) |
| **Discovery URL** | `https://auth.atparui.com/realms/{realm}/.well-known/openid-configuration` |
| **JWKS URL** | `https://auth.atparui.com/realms/{realm}/protocol/openid-connect/certs` |
| **Issuer** | `https://auth.atparui.com/realms/{realm}` |
| **Fineract (future)** | `spring.security.oauth2.resourceserver.jwt.issuer-uri=<Issuer>` and Keycloak-specific JWT converter |

---

## Frontend / client flow

1. User opens the Fineract UI (or any client).
2. Client redirects to Keycloak for login (e.g. `https://auth.atparui.com/realms/{realm}/protocol/openid-connect/auth` with `client_id`, `redirect_uri`, `response_type=code`, etc.).
3. After login, Keycloak returns an authorization code (or tokens) to the client.
4. Client exchanges the code for tokens (access_token, optionally refresh_token).
5. Client calls Fineract API with `Authorization: Bearer <access_token>`.
6. Fineract (as resource server) validates the JWT with Keycloak’s JWKS and uses the converter to build the security context (and optionally map to Fineract user/tenant).

---

## Checklist

- [ ] Keycloak realm and client created at auth.atparui.com  
- [ ] Discovery / JWKS URLs confirmed  
- [ ] Fineract config: `issuer-uri` (or `jwk-set-uri`) pointing to Keycloak realm  
- [ ] JWT converter updated or added for Keycloak (subject/username and roles)  
- [ ] Basic Auth disabled when Keycloak is enabled  
- [ ] Tenant handling (header/path) preserved if required  
- [ ] Frontend uses Keycloak for login and sends Bearer token to Fineract  

This document is based on public documentation for Spring Security OAuth2 Resource Server, Keycloak, and Fineract’s existing security code. Implementation details (property names, new classes) should be aligned with the actual Fineract codebase and your Keycloak realm/client setup.
