# IVR Token Authentication Engine — GitHub Guide

## 📖 Overview

This project implements a **multi-brand IVR Token Authentication Engine** with progressive authentication levels, customer preference filtering, and rule-driven path fallbacks. It is built with **Java 8 / Spring Boot 2.7.x** and uses **SQLite** for session storage and **JSON** for brand configuration.

Key capabilities:
- Multi-brand rule isolation with independent auth levels
- Shared token validators via a global registry
- Party disambiguation when ANI maps to multiple customers
- Customer preference filtering (blocked tokens, max level caps)
- Progressive authentication (start at `NONE`, escalate mid-session)
- Declarative JSON-driven rules — no code changes required for reconfiguration

---

## 🚀 Getting Started

### Prerequisites

- **JDK 8** (Java 1.8) — set `JAVA_HOME` accordingly
- **Maven 3.6+**
- **Git**

### Clone & Build

```bash
git clone <repo-url> ivr-auth-engine
cd ivr-auth-engine
mvn clean compile
```

### Run

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8081`.

### Run Tests

```bash
mvn test
```

---

## 📖 Swagger UI (API Testing)

This project includes **Springdoc OpenAPI** for interactive API documentation and testing.

### Access Swagger UI

Once the service is running, open your browser to:

```
http://localhost:8081/swagger-ui.html
```

### OpenAPI Spec

The raw OpenAPI 3.0 JSON spec is available at:

```
http://localhost:8081/v3/api-docs
```

### How to Test Drive the APIs

1. **Start the service** → `mvn spring-boot:run`
2. **Open Swagger UI** → navigate to `http://localhost:8081/swagger-ui.html`
3. **Expand the "IVR Authentication" section** to see all endpoints
4. **Try a full auth flow**:

   **Step 1 — Start a session:**
   - Click `POST /ivr/authenticate` → "Try it out"
   - Paste this body:
     ```json
     {
       "brandId": "BRAND_A",
       "callerId": "5551234567",
       "targetLevel": "STANDARD"
     }
     ```
   - Click "Execute" → copy the `sessionId` from the response

   **Step 2 — Submit an account number:**
   - Click `POST /ivr/authenticate` → "Try it out"
   - Paste the `sessionId` and body:
     ```json
     { "sessionId": "<id>", "tokenType": "ACCOUNT_NUMBER", "tokenValue": "123456789" }
     ```
   - Execute → you'll be prompted for PIN next

   **Step 3 — Submit a PIN:**
   - Same endpoint, same `sessionId`, body:
     ```json
     { "sessionId": "<id>", "tokenType": "PIN", "tokenValue": "1234" }
     ```
   - Execute → response shows `AUTHENTICATED` status

   **Step 4 — Escalate to ELEVATED:**
   - Click `POST /ivr/authenticate`
   - Body: `{ "sessionId": "<id>", "targetLevel": "ELEVATED" }`
   - Execute → prompted for OTP

   **Step 5 — Check status anytime:**
   - Click `GET /ivr/authenticate/{sessionId}/status`
   - Execute → see current level, validated tokens, etc.

### Swagger UI Features

- **"Try it out"** button on each endpoint lets you send real requests
- **Request bodies** are pre-populated with example values from `@Schema` annotations
- **Response codes and schemas** are documented for each endpoint
- **Schemas section** at the bottom shows all DTO structures

---

## 📁 Project Structure

```
src/
├── main/
│   ├── java/com/yourco/ivr/
│   │   ├── api/               # REST controllers + DTOs + exception handler
│   │   │   ├── AuthenticateController.java
│   │   │   ├── BrandController.java
│   │   │   ├── IvrExceptionHandler.java
│   │   │   ├── LookupServiceController.java
│   │   │   ├── SessionAdminController.java
│   │   │   ├── TransferPoliciesController.java
│   │   │   ├── action/        # RequestAction + RequestActionDiscriminator
│   │   │   └── dto/
│   │   ├── domain/            # Core domain model
│   │   │   ├── AuthLevel.java, TokenType.java, IvrSession.java, ...
│   │   │   └── config/        # Brand config model
│   │   ├── engine/            # Auth state machine
│   │   │   ├── AuthEngine.java
│   │   │   ├── AttemptCoordinator.java
│   │   │   ├── DisambiguationEngine.java
│   │   │   ├── PromptResolver.java
│   │   │   └── path/, preference/, response/, slot/, validation/
│   │   ├── service/           # AuthenticateService orchestrator
│   │   ├── validator/         # Token validation layer
│   │   │   ├── TokenValidator.java (interface)
│   │   │   ├── TokenValidatorRegistry.java
│   │   │   ├── ValidationResult.java
│   │   │   └── impl/          # AbstractTokenValidator + 7 validators
│   │   ├── lookup/            # Backend verification (SPI + registry + executor)
│   │   ├── partylookup/       # ANI → Party resolution
│   │   ├── preference/        # Customer preference provider
│   │   ├── registry/          # Brand config loader
│   │   ├── repository/        # SQLite session storage
│   │   └── exception/         # Custom exceptions
│   ├── resources/
│   │   ├── application.properties
│   │   ├── schema.sql
│   │   └── static/            # Admin Console SPA (built from src/main/ui/)
│   └── ui/                    # React admin console source (builds into resources/static/)
└── test/
    └── java/com/yourco/ivr/   # Integration + unit tests

config/brands/              # External brand JSON files (repo root)
├── brand_a.json
├── brand_b.json
├── id_only_brand.json
├── id_only_no_rules.json
└── test_brand.json

config/transfers/           # External transfer policy files (repo root)
└── transfer-policies.json
```

---

## 🔧 Configuration

### Brand Configurations (JSON)

Brand rules live in `./config/brands/*.json` (external directory). Each file defines:
- **`levelRules`** — one `LevelRule` per `AuthLevel` (BASIC, STANDARD, ELEVATED, etc.)
  - **`paths`** — ordered list of token paths; [0] = primary, [1..n] = fallbacks
    - **`pathIndex`** — 0-based position
    - **`description`** — human label
    - **`requiredTokens`** — all must validate to complete the path
    - **`backupTokens`** *(optional)* — map from required token to alternatives that satisfy the slot
  - **`maxRetriesPerToken`** — default attempts before path fallback or lockout
  - **`tokenRetryLimits`** — per-token retry overrides; values here take precedence over `maxRetriesPerToken`
  - **`lockoutSeconds`** — lockout duration when all paths exhausted

Add a new brand by creating a new `.json` file in the `config/brands/` directory and restarting, or via the UI/API.

### Application Properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session TTL in minutes |
| `ivr.session.cleanup.interval` | `60000` | Cleanup interval in ms |
| `ivr.brands.config-dir` | `./config/brands` | External brand config directory |
| `ivr.transfer.config-dir` | `./config/transfers` | External transfer policies directory |

---

## 📡 API Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate |
| `GET` | `/ivr/authenticate/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/authenticate/{id}` | End / hang up session |

### Example: Start a Session

```bash
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "brandId": "BRAND_A",
    "callerId": "5551234567",
    "targetLevel": "STANDARD"
  }'
```

---

## 📐 Architecture Decisions

| Decision | Rationale |
|---|---|
| **Java 8** | Enterprise compatibility; avoids migration overhead for legacy systems |
| **Spring Boot 2.7.x** | Last version supporting Java 8; stable and well-documented |
| **SQLite via JdbcTemplate** | Zero-configuration embedded database; no external server needed |
| **JSON for brand configs** | More portable than YAML; Jackson included by default in Spring Boot |
| **Stateless engine** | `AuthEngine` holds no state — all state in `IvrSession`, enabling horizontal scaling |
| **EnumMap/EnumSet** | Type-safe, memory-efficient collections for token/level tracking |

---

## 🧪 Testing

The project uses **Spring Boot Starter Test** (JUnit 4/5, Mockito).

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=IvrAuthIntegrationTest
```

---

## 🤝 Contributing

### Branch Strategy

- `main` — production-ready code
- `develop` — integration branch
- Feature branches: `feature/<short-description>`

### PR Checklist

- [ ] Code compiles (`mvn clean compile`)
- [ ] All tests pass (`mvn test`)
- [ ] **New unit/integration tests added for every new feature or behavior change**
- [ ] Brand JSON configs validated against `BrandAuthConfig` model
- [ ] No token values logged anywhere
- [ ] **README.md updated** with any new endpoints, config, or behavior
- [ ] **IVR_Auth_Engine_Technical_Spec.md updated** with any new or changed architecture

### ✅ Always Required for Every Change

| Change Type | Action Required |
|---|---|
| New endpoint, DTO, or API contract change | Update **Technical Spec** and **README** |
| New domain model (class, enum, field) | Update **Technical Spec** |
| New behavior (engine logic, flow change) | Update **Technical Spec** and add **tests** |
| New config property or JSON structure | Update **Technical Spec**, **README**, and **application.properties** comment |
| Bug fix | Add a **test** that reproduces the bug |
| Refactor | Ensure existing **tests still pass** |

> **The README, Technical Spec, and tests are not optional. They are part of the definition of done.**

---

## 🔒 Security Notes

- **Never log raw token values** (PINs, OTPs, SSN digits). Log only `tokenType` and validation outcome.
- Raw token values are never persisted to the database (the `collected_tokens` column is always null). Values exist in memory only for the duration of a single request.
- Session IDs are UUIDs — no sequential IDs.
- Lockout is enforced server-side; cannot be bypassed by restarting the session.
