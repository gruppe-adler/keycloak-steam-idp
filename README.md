# Keycloak Steam Identity Provider

A Keycloak SPI plugin written in **Kotlin** that lets users log in with their Steam account via OpenID 2.0, and enriches the brokered identity with **username** and **avatar** fetched from the Steam Web API.

---

## How it works

```
Browser → Keycloak → steamcommunity.com (OpenID checkid_setup)
                  ← redirect back with openid.* params
Keycloak → steamcommunity.com (check_authentication, back-channel)
         → api.steampowered.com/ISteamUser/GetPlayerSummaries
         → build BrokeredIdentityContext (steamId, username, avatarUrl, profileUrl)
         → standard Keycloak broker flow (first-broker-login / account linking)
```

---

## File layout

```
src/main/kotlin/dev/example/keycloak/steam/
  SteamIdentityProviderConfig.kt   – typed config wrapper + constants
  SteamIdentityProvider.kt         – core provider (login redirect, OpenID verify, identity build)
  SteamCallbackEndpoint.kt         – JAX-RS endpoint for Steam's return redirect
  SteamIdentityProviderFactory.kt  – SPI factory registered via ServiceLoader
  SteamUserAttributeMapper.kt      – attribute mapper (steamId / username / avatar / profile)
  SteamApiClient.kt                – thin JDK-HttpClient wrapper for Steam Web API

src/main/resources/META-INF/services/
  org.keycloak.broker.provider.IdentityProviderFactory  – factory registration
  org.keycloak.broker.provider.IdentityProviderMapper   – mapper registration
```

---

## Prerequisites

| Tool     | Version |
|----------|---------|
| JDK      | 17+     |
| Maven    | 3.8+    |
| Keycloak | 26.6.3+ |

---

## Build

```bash
mvn clean package -DskipTests
# → target/keycloak-steam-provider-1.0.0.jar  (shaded, includes Kotlin stdlib)
```

---

## Deployment

### Keycloak 24+ (Quarkus distribution)

```bash
# 1. Copy the JAR into the providers directory
cp target/keycloak-steam-provider-1.0.0.jar $KEYCLOAK_HOME/providers/

# 2. Rebuild Keycloak's optimized image (required once per new provider)
$KEYCLOAK_HOME/bin/kc.sh build

# 3. Start Keycloak
$KEYCLOAK_HOME/bin/kc.sh start
```

### Docker / Kubernetes

```dockerfile
FROM quay.io/keycloak/keycloak:26.6.3

COPY target/keycloak-steam-provider-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start"]
```

---

## Configuration in the Admin Console

1. Open **Realm Settings → Identity Providers → Add provider → Steam**.
2. Fill in:

   | Field                 | Value                                                            |
   |-----------------------|------------------------------------------------------------------|
   | **Redirect URI**      | Auto-filled — copy this to Steam's allowed return URLs if needed |
   | **Steam Web API Key** | Your key from <https://steamcommunity.com/dev/apikey>            |
   | **Sync mode**         | `force` (re-imports profile on every login)                      |

3. Click **Save**.

### Mappers (optional but recommended)

Under **Identity Providers → Steam → Mappers → Add mapper**, choose
**Steam Attribute Importer** and configure:

| Steam Attribute   | User Attribute |
|-------------------|----------------|
| `steamId`         | `steamId`      |
| `steamUsername`   | `nickname`     |
| `steamAvatarUrl`  | `picture`      |
| `steamProfileUrl` | `profile`      |

These Keycloak user attributes can then be exposed as claims in your tokens via
**Client Scopes → profile → Mappers**.

---

## Claims written to `BrokeredIdentityContext`

| Attribute key       | Content                                              |
|---------------------|------------------------------------------------------|
| `steamId`           | 17-digit Steam ID 64                                 |
| `steamUsername`     | Player's in-game persona name                        |
| `steamAvatarUrl`    | Full-size avatar JPEG URL (184×184)                  |
| `steamProfileUrl`   | Public Steam community profile URL                   |

---

## Security notes

* **Back-channel verification** — the `check_authentication` call is made
  server-to-server, so the assertion cannot be forged by a malicious redirect.
* **API key storage** — the key is stored as a Keycloak `SECRET_TYPE` config
  property; it is masked in the Admin Console and encrypted in the database.
* The provider does **not** use `openid.sreg` or `ax` extensions — only the
  canonical SteamID is derived from the claimed identity URL.

---

## Extending

* **Custom claims** — add fields to `SteamPlayerSummary` and populate them in
  `SteamApiClient.parsePlayerSummary`, then set them in
  `SteamIdentityProvider.buildIdentityContext`.
* **Caching** — wrap `SteamApiClient.getPlayerSummary` with a Keycloak
  `InfinispanConnectionProvider` cache to reduce Web API calls on token refresh.
* **Friends / groups** — add additional `ISteamUser` calls in `SteamApiClient`
  and store results as multivalued user attributes for group mapping.
