# IVR Token Authentication Engine

> **Multi-Brand | Progressive Auth Levels | Rule-Driven**  
> Java 8 · Spring Boot 2.7.x · SQLite · OpenAPI 3.0

A production-ready engine for IVR (Interactive Voice Response) systems that need **multi-brand authentication with progressive security levels**, **token-sharing across brands**, and **declarative JSON-driven rules** — all without code changes.

---

## ✨ Features

- **Multi-brand isolation** — Each brand defines its own auth levels, token paths, retry limits, and lockout policies
- **Progressive authentication** — Sessions start at `NONE` and step up to the target level; mid-session escalation is supported
- **Path fallbacks** — When the primary token path is exhausted, the engine automatically falls back to a configured alternative path
- **Cross-brand token sharing** — Globally or conditionally share validated tokens across brands within the same session, with TTL controls
- **Declarative JSON config** — All brand rules live in `resources/brands/*.json`; no code changes needed to add or modify brands
- **Stateless engine** — `AuthEngine` holds no state, enabling horizontal scaling
- **Interactive API docs** — Swagger UI built in via Springdoc OpenAPI

---

## 🧱 Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| REST API | Spring MVC | Accepts IVR platform calls on 5 endpoints |
| Auth Engine | Plain Java (Spring `@Service`) | Core state machine — evaluates rules, drives path progression |
| Rules Registry | Jackson + classpath JSON | Loads and caches `BrandAuthConfig` objects at startup |
| Validator Registry | Spring Bean Discovery | Maps `TokenType` → `TokenValidator` implementations |
| Session Store | SQLite + JdbcTemplate | Persists `IvrSession` with full token/level state as JSON columns |
| API Docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI from annotations |

---

## 🚀 Quick Start

### Prerequisites

- [JDK 8](https://adoptium.net/temurin/releases/?version=8) (Java 1.8)
- [Maven 3.6+](https://maven.apache.org/download.cgi)

### Run the service

```bash
# Clone (if you haven't)
git clone <repo-url> ivr-auth-engine
cd ivr-auth-engine

# Build & start
mvn spring-boot:run
```

The service starts on **`http://localhost:8081`** (port 8080 may be in use on some systems — change in `application.properties`).

### Open Swagger UI

Once running, open your browser to:

```
http://localhost:8081/swagger-ui.html
```

Swagger UI shows all 5 endpoints with request/response schemas. Use the **"Try it out"** button to send real requests.

---

## 📡 API Overview

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/ivr/session/start` | Create a new session for a brand + target level |
| `POST` | `/ivr/session/{id}/token` | Submit a collected token (PIN, OTP, etc.) |
| `POST` | `/ivr/session/{id}/escalate` | Request a higher auth level mid-session |
| `GET` | `/ivr/session/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/session/{id}` | End / hang up a session |

### 🔄 Full Auth Flow Example

```bash
# 1. Start a session
curl -X POST http://localhost:8081/ivr/session/start \
  -H "Content-Type: application/json" \
  -d '{"brandId":"BRAND_A","callerId":"5551234567","targetLevel":"STANDARD"}'

# Response → { "nextRequiredToken": "ACCOUNT_NUMBER", ... }
# Copy the sessionId from the response.

# 2. Submit account number
curl -X POST http://localhost:8081/ivr/session/{sessionId}/token \
  -H "Content-Type: application/json" \
  -d '{"tokenType":"ACCOUNT_NUMBER","tokenValue":"123456789"}'

# Response → { "nextRequiredToken": "PIN", ... }

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

All brand behaviour is driven by JSON files in `src/main/resources/brands/`.

### Brand A (full example — `brand-a.json`)

```json
{
  "brandId": "BRAND_A",
  "levelRules": {
    "BASIC": {
      "paths": [{ "pathIndex": 0, "requiredTokens": ["ACCOUNT_NUMBER"] }],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 0
    },
    "STANDARD": {
      "paths": [
        { "pathIndex": 0, "requiredTokens": ["ACCOUNT_NUMBER", "PIN"] },
        { "pathIndex": 1, "requiredTokens": ["ACCOUNT_NUMBER", "OTP"] }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    },
    "ELEVATED": {
      "paths": [
        { "pathIndex": 0, "requiredTokens": ["ACCOUNT_NUMBER", "PIN", "OTP"] },
        { "pathIndex": 1, "requiredTokens": ["ACCOUNT_NUMBER", "VOICE_PRINT", "OTP"] }
      ],
      "maxRetriesPerToken": 2,
      "lockoutSeconds": 600
    }
  },
  "sharingPolicy": {
    "globallySharedTokens": ["ACCOUNT_NUMBER"],
    "conditionallySharedFrom": { "OTP": ["BRAND_B"], "PIN": ["BRAND_B"] },
    "crossBrandTokenMaxAgeSeconds": 1800
  }
}
```

To add a new brand, create a new `.json` file in the `brands/` directory and restart.

### Application Properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP server port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session time-to-live |
| `ivr.session.cleanup.interval` | `60000` | Expired session cleanup interval (ms) |

---

## 📁 Project Structure

```
src/main/java/com/yourco/ivr/
├── api/                    # REST layer
│   ├── SessionController.java
│   ├── IvrExceptionHandler.java
│   └── dto/                # Request/Response DTOs
├── domain/                 # Core domain model
│   ├── AuthLevel.java          # Auth level enum with rank
│   ├── TokenType.java          # 7 token types
│   ├── IvrSession.java         # Full session state
│   ├── SessionStatus.java      # Session lifecycle states
│   ├── CrossBrandTokenRecord.java
│   └── config/             # Brand configuration model
├── engine/                 # Auth state machine
│   ├── AuthEngine.java         # Core engine
│   ├── CrossBrandTokenEvaluator.java
│   └── PromptResolver.java
├── service/
│   └── SessionService.java     # Orchestrator
├── validator/
│   ├── TokenValidator.java     # Interface
│   ├── TokenValidatorRegistry.java
│   └── impl/               # 7 stub validators
├── registry/
│   ├── BrandRulesRegistry.java
│   └── BrandRulesLoader.java   # JSON loader
├── repository/
│   ├── SessionRepository.java
│   └── SqliteSessionRepository.java
└── exception/              # 5 custom exceptions

src/main/resources/
├── application.properties
├── schema.sql
├── brands/
│   ├── brand-a.json
│   └── brand-b.json
└── OpenApiConfig.java
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
