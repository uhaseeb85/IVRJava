# IVR Token Authentication Engine

> **Multi-Brand | Progressive Auth Levels | Rule-Driven**  
> Java 8 · Spring Boot 2.7.x · SQLite · OpenAPI 3.0

A production-ready engine for IVR systems that need **multi-brand authentication with progressive security levels**, **automatic cross-brand token sharing**, **backup token alternatives**, and **declarative JSON-driven rules** — all without code changes.

---

## ✨ Features

- **Multi-brand isolation** — Each brand defines its own auth levels, token paths, retry limits, and lockout policies
- **Progressive authentication** — Sessions start at `NONE` and step up to the target level; mid-session escalation is supported
- **Path fallbacks** — When the primary token path is exhausted, the engine automatically falls back to a configured alternative path
- **Backup token alternatives** — Each required token can declare alternative token types that the client may submit instead (e.g. accept `SSN_LAST4` or `DATE_OF_BIRTH` in place of `PIN`)
- **Automatic cross-brand token sharing** — Any token validated in one brand's session is automatically reusable in another brand's session — no per-brand policy configuration needed
- **Initial tokens at session start** — Clients can submit pre-collected tokens when creating a session
- **Declarative JSON config** — All brand rules live in `./config/brands/*.json`; no code changes needed to add or modify brands
- **Brand Config Editor UI** — Web-based editor at `http://localhost:8081/` to create, view, update, and delete brand configs
- **Stateless engine** — `AuthEngine` holds no state, enabling horizontal scaling
- **Interactive API docs** — Swagger UI built in via Springdoc OpenAPI

---

## 🧱 Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| REST API | Spring MVC | Accepts IVR platform calls on 5 session endpoints + brand CRUD |
| Auth Engine | Plain Java (Spring `@Service`) | Core state machine — evaluates rules, drives path progression |
| Rules Registry | Jackson + external JSON | Loads and caches `BrandAuthConfig` objects from `./config/brands/` |
| Validator Registry | Spring Bean Discovery | Maps `TokenType` → `TokenValidator` implementations |
| Session Store | SQLite + JdbcTemplate | Persists `IvrSession` with full token/level state as JSON columns |
| Brand Config API | Spring MVC + File I/O | CRUD endpoints for managing brand JSON files |
| Brand Editor UI | React SPA (in-browser Babel) | Visual editor for brand configurations |
| API Docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI from annotations |

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
| `POST` | `/ivr/session/start` | Create a new session for a brand + target level |
| `POST` | `/ivr/session/{id}/token` | Submit a collected token (PIN, OTP, etc.) |
| `POST` | `/ivr/session/{id}/escalate` | Request a higher auth level mid-session |
| `GET` | `/ivr/session/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/session/{id}` | End / hang up a session |

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
curl -X POST http://localhost:8081/ivr/session/start \
  -H "Content-Type: application/json" \
  -d '{"brandId":"BRAND_A","callerId":"5551234567","targetLevel":"STANDARD"}'

# Response → { "nextRequiredToken": "ACCOUNT_NUMBER",
#              "acceptedTokens": ["ACCOUNT_NUMBER"], ... }
# Copy the sessionId from the response.

# 2. Submit account number
curl -X POST http://localhost:8081/ivr/session/{sessionId}/token \
  -H "Content-Type: application/json" \
  -d '{"tokenType":"ACCOUNT_NUMBER","tokenValue":"123456789"}'

# Response → { "nextRequiredToken": "PIN",
#              "acceptedTokens": ["PIN", "SSN_LAST4", "DATE_OF_BIRTH"], ... }
# Note: PIN can be substituted with SSN_LAST4 or DATE_OF_BIRTH as per backupTokens config.

# 3. Submit PIN → authenticated at STANDARD level
curl -X POST http://localhost:8081/ivr/session/{sessionId}/token \
  -H "Content-Type: application/json" \
  -d '{"tokenType":"PIN","tokenValue":"1234"}'

# Response → { "status": "AUTHENTICATED", "currentLevel": "STANDARD", ... }

# 4. Escalate to ELEVATED
curl -X POST http://localhost:8081/ivr/session/{sessionId}/escalate \
  -H "Content-Type: application/json" \
  -d '{"targetLevel":"ELEVATED"}'

# Response → { "nextRequiredToken": "OTP", ... }
```

---

## ⚙️ Configuration

### Brand Configs (JSON)

Brand configs are stored in `./config/brands/*.json` (external to the JAR). They persist across restarts and can be managed via the Brand Editor UI at `http://localhost:8081/`.

### Brand A (full example — `brand-a.json`)

```json
{
  "brandId": "BRAND_A",
  "levelRules": {
    "BASIC": {
      "paths": [
        { "pathIndex": 0, "description": "Account lookup", "requiredTokens": ["ACCOUNT_NUMBER"] }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 0
    },
    "STANDARD": {
      "paths": [
        { "pathIndex": 0, "description": "Account + PIN",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"],
          "backupTokens": { "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"] } },
        { "pathIndex": 1, "description": "Account + OTP fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"] }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    },
    "ELEVATED": {
      "paths": [
        { "pathIndex": 0, "description": "Full factor",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN", "OTP"],
          "backupTokens": { "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"] } },
        { "pathIndex": 1, "description": "Voice biometric fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "VOICE_PRINT", "OTP"] }
      ],
      "maxRetriesPerToken": 2,
      "lockoutSeconds": 600
    }
  }
}
```

### Application Properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP server port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session time-to-live |
| `ivr.session.cleanup.interval` | `60000` | Expired session cleanup interval (ms) |
| `ivr.brands.config-dir` | `./config/brands` | External brand config directory |

---

## 📁 Project Structure

```
src/main/java/com/yourco/ivr/
├── api/                    # REST layer
│   ├── SessionController.java       # Session endpoints
│   ├── BrandController.java         # Brand CRUD endpoints
│   ├── IvrExceptionHandler.java     # Global error handler
│   └── dto/                         # Request/Response DTOs
├── domain/                 # Core domain model
│   ├── AuthLevel.java              # Auth level enum with rank
│   ├── TokenType.java              # 7 token types
│   ├── IvrSession.java             # Full session state
│   ├── SessionStatus.java          # Session lifecycle states
│   ├── CrossBrandTokenRecord.java
│   └── config/                     # Brand config model
├── engine/                 # Auth state machine
│   ├── AuthEngine.java             # Core engine
│   ├── CrossBrandTokenEvaluator.java
│   └── PromptResolver.java
├── service/
│   ├── SessionService.java         # Session orchestrator
│   └── BrandService.java           # Brand file CRUD orchestrator
├── validator/
│   ├── TokenValidator.java         # Interface
│   ├── TokenValidatorRegistry.java
│   └── impl/                       # 7 stub validators
├── registry/
│   ├── BrandRulesRegistry.java
│   └── BrandRulesLoader.java       # Loads configs at startup
├── repository/
│   ├── SessionRepository.java
│   └── SqliteSessionRepository.java
├── exception/              # Custom exceptions
├── IvrAuthEngineApplication.java
└── OpenApiConfig.java

src/main/resources/
├── application.properties
├── schema.sql
└── static/index.html        # Brand Config Editor SPA

config/brands/               # External brand config directory
├── brand-a.json
└── brand-b.json
```

---

## 🧪 Running Tests

```bash
mvn test
```

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

---

## 🔒 Security Considerations

- **Never log raw token values** — log only `tokenType` and validation outcome
- Token values in `collectedTokens` should be encrypted at rest (see Phase 6 in the technical spec)
- Session IDs are UUIDs — no sequential enumeration possible
- Lockout is enforced server-side and cannot be bypassed
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
