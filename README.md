# IVR Token Authentication Engine

> **Multi-Brand | Progressive Auth Levels | Rule-Driven**  
> Java 8 · Spring Boot 2.7.x · SQLite · OpenAPI 3.0

A production-ready engine for IVR systems that need **multi-brand authentication with progressive security levels**, **backup token alternatives**, **party disambiguation via ANI**, **customer-specific preference filtering**, and **declarative JSON-driven rules**.

---

## ✨ Features

- **Multi-brand isolation** — Each brand defines its own auth levels, token paths, retry limits, and fail policies
- **Progressive authentication** — Sessions start at `NONE` and step up to the target level; mid-session escalation is supported
- **Path fallbacks** — When the primary token path is exhausted, the engine automatically falls back to a configured alternative path before failing
- **Backup token alternatives** — Each required token can declare alternative token types that the client may submit instead (e.g. accept `SSN_LAST4` or `DATE_OF_BIRTH` in place of `PIN`)
- **Party Disambiguation** — When an ANI maps to multiple parties (customers), the engine applies configurable disambiguation rules and requests differentiating tokens to resolve to a single party
- **Customer Preference Filtering** — Once a party is identified, customer-specific preferences (e.g., blocked token types) are loaded and used to filter which tokens are offered — blocked tokens are automatically skipped and backup alternatives or fallback paths are used instead
- **Call Transfer support** — Accept calls transferred from external IVR systems with pre-validated tokens; per-source policies control which tokens and auth levels are honored
- **Optimistic locking** — Version-based concurrency control on session updates prevents lost writes under concurrent requests
- **Structured audit logging** — Auth events (token pass/fail, escalation, lockout) logged by session with caller and brand context
- **Initial tokens at session start** — Clients can submit pre-collected tokens when creating a session
- **Declarative JSON config** — All brand rules live in `./config/brands/*.json`; no code changes needed to add or modify brands
- **Brand Config Editor UI** — Web-based editor at `http://localhost:8081/` to create, view, update, and delete brand configs
- **Stateless engine** — `AuthEngine` holds no state, enabling horizontal scaling
- **Interactive API docs** — Swagger UI built in via Springdoc OpenAPI

---

## 🧱 Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| REST API | Spring MVC | Accepts IVR platform calls on 6 session endpoints + brand CRUD |
| Auth Engine | Plain Java (Spring `@Service`) | Core state machine — evaluates rules, drives path progression |
| Rules Registry | Jackson + external JSON | Loads and caches `BrandAuthConfig` objects from `./config/brands/` |
| Transfer Policies Registry | Jackson + external JSON | Loads per-source `TransferPolicy` objects from `./config/transfers/` |
| Validator Registry | Spring Bean Discovery | Maps `TokenType` → `TokenValidator` implementations |
| Session Store | SQLite + JdbcTemplate | Persists `IvrSession` with full token/level/party/preference state as JSON columns; optimistic locking via version column |
| Party Lookup | Pluggable interface | Looks up parties by ANI; stub returns a single generic party |
| Disambiguation Engine | Plain Java | Applies rules, selects differentiating tokens, resolves to single party |
| Customer Preference Provider | Pluggable interface | Loads customer preferences (blocked tokens, max level); stub returns empty |
| Brand Config API | Spring MVC + File I/O | CRUD endpoints for managing brand JSON files |
| Brand Editor UI | React + Vite + Tailwind CSS (shadcn-style) | Visual editor for brand configurations at `http://localhost:8081/` |
| API Docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI at `http://localhost:8081/swagger-ui.html` |

---

## 🚀 Quick Start

### Prerequisites

- [JDK 8](https://adoptium.net/temurin/releases/?version=8) (Java 1.8)
- [Maven 3.6+](https://maven.apache.org/download.cgi)

### Run the service

```bash
git clone <repo-url> ivr-auth-engine
cd ivr-auth-engine
mvn spring-boot:run
```

The service starts on **`http://localhost:8081`**.

### Open Swagger UI

```
http://localhost:8081/swagger-ui.html
```

### Open Brand Config Editor

```
http://localhost:8081/
```

---

## 📡 API Overview

### Session Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate (discriminated by payload) |
| `GET` | `/ivr/authenticate/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/authenticate/{id}` | End / hang up a session |

### Brand Config Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/brands` | List all brand configs |
| `GET` | `/api/brands/{id}` | Get a brand config |
| `POST` | `/api/brands` | Create a new brand config |
| `PUT` | `/api/brands/{id}` | Update an existing brand config |
| `DELETE` | `/api/brands/{id}` | Delete a brand config |

### 🔄 Full Auth Flow Example

```bash
# 1. Start a session
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"brandId":"BRAND_A","callerId":"5551234567","targetLevel":"STANDARD"}'

# Response → { "nextRequiredToken": "ACCOUNT_NUMBER", ... }
# Copy the sessionId from the response.

# 2. Submit account number
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","tokenType":"ACCOUNT_NUMBER","tokenValue":"123456789"}'

# 3. Submit PIN → authenticated at STANDARD level
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","tokenType":"PIN","tokenValue":"1234"}'

# Response → { "status": "AUTHENTICATED", "currentLevel": "STANDARD", ... }

# 4. Escalate to ELEVATED
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","targetLevel":"ELEVATED"}'
```

### Initial Tokens at Session Start

Clients can submit pre-collected tokens when creating a session:

```bash
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "brandId": "BRAND_A",
    "callerId": "5551234567",
    "targetLevel": "STANDARD",
    "initialTokens": {"ACCOUNT_NUMBER": "123456789"}
  }'
```

The engine processes initial tokens through the same validation pipeline before returning the first response.

### 🚚 Call Transfer

Accept a caller transferred from an external system with pre-validated tokens:

```bash
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "sourceSystemId": "LEGACY_IVR",
    "brandId": "BRAND_A",
    "callerId": "5551234567",
    "currentLevel": "BASIC",
    "targetLevel": "STANDARD",
    "validatedTokens": ["ACCOUNT_NUMBER"]
  }'
```

Tokens are filtered per the source system's transfer policy (see `./config/transfers/`). The caller's `currentLevel` is capped at the policy's `maxHonoredLevel`. Attempt counts always reset.

---

## ⚙️ Configuration

### Brand Configs (JSON)

Brand configs are stored in `./config/brands/*.json` (external to the JAR). They persist across restarts and can be managed via the Brand Editor UI at `http://localhost:8081/`.

Each brand config has the following structure:

- **`brandId`** — unique brand identifier (e.g. `BRAND_A`)
- **`levelRules`** — map of `AuthLevel` → `LevelRule`:
  - **`paths`** — ordered list of `TokenPath` objects. `paths[0]` is the primary path; `paths[1..n]` are fallback paths activated when retries are exhausted on the current path
    - **`pathIndex`** — position in the path list
    - **`description`** — human-readable label
    - **`requiredTokens`** — ordered list of `TokenType` values that must all be validated to complete this path
    - **`backupTokens`** *(optional)* — map from a required token to alternative token types the client may submit. The client is told which tokens are accepted via the `acceptedTokens` response field. However, the required token itself must still be collected directly for the path to complete.
  - **`maxRetriesPerToken`** — number of failed attempts allowed per token type before triggering a path fallback or failing the session

### Brand A (full example)

```json
{
  "brandId": "BRAND_A",
  "levelRules": {
    "BASIC": {
      "paths": [
        { "pathIndex": 0, "description": "Account lookup", "requiredTokens": ["ACCOUNT_NUMBER"] }
      ],
      "maxRetriesPerToken": 3
    },
    "STANDARD": {
      "paths": [
        { "pathIndex": 0, "description": "Account + PIN",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"],
          "backupTokens": { "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"] } },
        { "pathIndex": 1, "description": "Account + OTP fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"] }
      ],
      "maxRetriesPerToken": 3
    },
    "ELEVATED": {
      "paths": [
        { "pathIndex": 0, "description": "Full factor",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN", "OTP"],
          "backupTokens": { "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"] } },
        { "pathIndex": 1, "description": "Voice biometric fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "VOICE_PRINT", "OTP"] }
      ],
      "maxRetriesPerToken": 2
    }
  }
}
```

### Party Disambiguation & Customer Preferences

Party disambiguation is always-on for all brands. On session start, the engine calls `PartyLookupProvider.lookupByAni(callerId)` (pluggable interface) to find parties matching the ANI.

**How it works:**
1. 0 parties → **400 error** (unknown caller)
2. 1 party → skips disambiguation, loads `CustomerPreferenceProvider.getPreferences(partyId)`, proceeds to auth
3. N parties → applies configured rules from `DisambiguationConfig`, then asks for differentiating tokens to resolve to a single party

The `DisambiguationConfig` is a Java class with defaults (`maxDisambiguationTokens=3`, no rules). Brands can add a `"disambiguation"` block to their JSON to override these defaults.

```json
{
  "brandId": "BRAND_A",
  "disambiguation": {
    "maxDisambiguationTokens": 5,
    "rules": [
      { "type": "EXCLUDE_INACTIVE" },
      { "type": "PREFER_PRIMARY_ANI" }
    ]
  },
  "levelRules": { ... }
}
```

**Rule types:** `EXCLUDE_INACTIVE` (filters !active), `PREFER_PRIMARY_ANI` (keeps primaryAni=true).

**Customer Preferences** control which tokens are offered:
- `blockedTokens` — tokens excluded from prompts; engine tries backups or advances to next path
- `maxAllowedLevel` — caps the maximum auth level for this customer

To integrate real backends, replace the stub implementations:
- `PartyLookupProvider` → point to your CRM/account system API
- `CustomerPreferenceProvider` → point to your customer preferences datastore

### Application Properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP server port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session time-to-live |
| `ivr.session.cleanup.interval` | `60000` | Expired session cleanup interval (ms) |
| `ivr.brands.config-dir` | `./config/brands` | External brand config directory |
| `ivr.transfer.config-dir` | `./config/transfers` | External transfer policies directory |

### Transfer Policies (JSON)

Per-source transfer policies control which external systems can transfer calls and what tokens/levels are honored:

```json
{
  "policies": [
    {
      "sourceSystemId": "LEGACY_IVR",
      "honoredTokens": ["ACCOUNT_NUMBER", "PIN", "OTP", "SSN_LAST4", "DATE_OF_BIRTH"],
      "maxHonoredLevel": "STANDARD",
      "enabled": true
    },
    {
      "sourceSystemId": "SALESFORCE",
      "honoredTokens": ["ACCOUNT_NUMBER"],
      "maxHonoredLevel": "BASIC",
      "enabled": true
    }
  ]
}
```

- **`honoredTokens`** — token types trusted from this source
- **`maxHonoredLevel`** — highest auth level honored (caller's claimed level is capped)
- **`enabled`** — toggle the source on/off

---

## 📁 Project Structure

```
src/main/java/com/yourco/ivr/
├── api/                    # REST layer
│   ├── AuthenticateController.java  # Unified session endpoints (3 total)
│   ├── BrandController.java         # Brand CRUD endpoints
│   ├── IvrExceptionHandler.java     # Global error handler
│   └── dto/                         # Request/Response DTOs
│       ├── CallTransferRequest.java  # Call transfer DTO
│       └── ...
├── domain/                 # Core domain model
│   ├── AuthLevel.java              # Auth level enum with rank
│   ├── TokenType.java              # 7 token types
│   ├── SessionPhase.java           # DISAMBIGUATION / AUTHENTICATING
│   ├── IvrSession.java             # Full session state (versioned)
│   ├── SessionStatus.java          # Session lifecycle states
│   ├── Party.java                  # Customer party record
│   ├── CustomerPreference.java     # Blocked tokens, max level caps
│   ├── ValidationResult.java       # Generic validation result
│   ├── CrossBrandTokenRecord.java
│   └── config/                     # Brand config model + transfer policy
│       ├── BrandAuthConfig.java
│       ├── DisambiguationConfig.java
│       ├── LevelRule.java
│       ├── TokenPath.java
│       ├── TransferPolicy.java
│       └── TransferPoliciesConfig.java
├── engine/                 # Auth state machine
│   ├── AuthEngine.java             # Core engine (disambig routing + pref filtering + audit logging)
│   ├── CrossBrandTokenEvaluator.java
│   ├── DisambiguationEngine.java   # Party resolution + token matching
│   ├── DisambiguationRule.java     # Rule interface
│   ├── PromptResolver.java
│   └── impl/
│       ├── ExcludeInactiveRule.java
│       └── PrimaryAniRule.java
├── partylookup/            # ANI → Party resolution
│   ├── PartyLookupProvider.java
│   └── StubPartyLookupProvider.java
├── preference/             # Customer preferences
│   ├── CustomerPreferenceProvider.java
│   └── StubCustomerPreferenceProvider.java
├── service/
│   ├── AuthenticateService.java    # Session orchestrator
│   └── BrandService.java           # Brand file CRUD orchestrator
├── validator/
│   ├── TokenValidator.java         # Interface
│   ├── TokenValidatorRegistry.java
│   └── impl/                       # 7 stub validators
├── registry/
│   ├── BrandRulesRegistry.java
│   ├── BrandRulesLoader.java       # Loads brand configs at startup
│   └── TransferPoliciesRegistry.java # Loads transfer policies at startup
├── repository/
│   ├── DatabaseConfig.java         # DB schema initializer
│   ├── SessionRepository.java      # Interface
│   └── SqliteSessionRepository.java # SQLite + JdbcTemplate + optimistic locking
├── exception/              # Custom exceptions
│   ├── SessionNotFoundException.java
│   ├── SessionLockedException.java
│   ├── SessionConflictException.java
│   ├── SessionSerializationException.java
│   ├── TransferNotAllowedException.java
│   ├── UnknownBrandException.java
│   ├── UnknownCallerException.java
│   ├── BrandConfigException.java
│   └── UnsupportedTokenTypeException.java
├── IvrAuthEngineApplication.java
└── OpenApiConfig.java

src/main/resources/
├── application.properties
├── schema.sql
└── static/index.html        # Brand Config Editor SPA

config/brands/               # External brand config directory (loaded at startup)
├── brand_a.json              # BRAND_A — full example with 3 levels, backup tokens
└── brand_b.json              # BRAND_B — simpler config with 2 levels

config/transfers/             # External transfer policy directory
└── transfer-policies.json    # Per-source token/level policies

src/test/java/com/yourco/ivr/
└── IvrAuthIntegrationTest.java           # 12 integration tests (auth, transfer, backup, fallback)
└── DisambiguationAndPreferenceTest.java   # 12 integration tests (disambiguation + preferences, uses MockBean)
```

---

## 🧪 Running Tests

```bash
mvn test
```

### UI Development

The brand config editor is a standalone Vite + React + TypeScript + Tailwind CSS project at `src/main/ui/`.

```bash
cd src/main/ui
npm install
npm run dev          # starts Vite dev server on :5173, proxies API to :8081
npm run build        # builds static files into src/main/resources/static/
```

The dev server proxies `/api/*` and `/ivr/*` to `http://localhost:8081`. Run `mvn spring-boot:run` in a separate terminal first.

---

## 🛠 Tech Stack

| Component | Choice | Rationale |
|---|---|---|
| Language | Java 8 | Enterprise compatibility |
| Framework | Spring Boot 2.7.18 | Last Spring Boot version supporting Java 8 |
| Database | SQLite (via JdbcTemplate) | Zero-config embedded database |
| Config Format | JSON (via Jackson) | Portable, well-supported by Spring Boot |
| API Docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI from annotations |
| Build | Maven | Industry standard for enterprise Java |
| Code Gen | Lombok | Reduces boilerplate |
| Testing | JUnit 5 + SpringBootTest | Integration tests with real HTTP calls |

---

## 🔒 Security Considerations

- **Never log raw token values** — log only `tokenType` and validation outcome
- Token values in `collectedTokens` should be encrypted at rest (see Phase 6 in the technical spec)
- Session IDs are UUIDs — no sequential enumeration possible
- Token values are stored in `collectedTokens` map and submitted via API — use HTTPS in production

---

## 📚 Documentation

- **[Technical Spec](IVR_Auth_Engine_Technical_Spec.md)** — Full system design document (must stay in sync with code changes)
- **[GitHub Guide](.github/github-instructions.md)** — Contribution workflow, branching strategy, and PR checklist
- **[Swagger UI](http://localhost:8081/swagger-ui.html)** — Interactive API documentation (run the service first)
- **[Brand Config Editor](http://localhost:8081/)** — Web UI for managing brand configurations

---

## 🤝 Contributing

See the [GitHub Guide](.github/github-instructions.md) for:
- Branch strategy and PR checklist
- Coding conventions
- **Critical: Keeping the Technical Spec updated** with every code change

---

## 📄 License

Proprietary — Internal Use

---

## 🔒 Security Considerations

- **Never log raw token values** — log only `tokenType` and validation outcome
- Token values in `collectedTokens` should be encrypted at rest (see Phase 6 in the technical spec)
- Session IDs are UUIDs — no sequential enumeration possible
- Lockout is enforced server-side and cannot be bypassed

---

## 📚 Documentation

- **[Technical Spec](IVR_Auth_Engine_Technical_Spec.md)** — Full system design document (must stay in sync with code changes)
- **[GitHub Guide](.github/github-instructions.md)** — Contribution workflow, branching strategy, and PR checklist
- **[Swagger UI](http://localhost:8081/swagger-ui.html)** — Interactive API documentation (run the service first)

---

## 🤝 Contributing

See the [GitHub Guide](.github/github-instructions.md) for:
- Branch strategy and PR checklist
- Coding conventions
- **Critical: Keeping the Technical Spec updated** with every code change

---

## 📄 License

Proprietary — Internal Use
