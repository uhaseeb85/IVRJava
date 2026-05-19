# IVR Token Authentication Engine — GitHub Guide

## 📖 Overview

This project implements a **multi-brand IVR Token Authentication Engine** with progressive authentication levels, token sharing policies, and rule-driven path fallbacks. It is built with **Java 8 / Spring Boot 2.7.x** and uses **SQLite** for session storage and **JSON** for brand configuration.

Key capabilities:
- Multi-brand rule isolation with independent auth levels
- Shared token validators via a global registry
- Cross-brand token sharing with TTL-based policies
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

The application starts on `http://localhost:8080`.

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
http://localhost:8080/swagger-ui.html
```

(or `http://localhost:8081/swagger-ui.html` if port 8080 is occupied)

### OpenAPI Spec

The raw OpenAPI 3.0 JSON spec is available at:

```
http://localhost:8080/v3/api-docs
```

### How to Test Drive the APIs

1. **Start the service** → `mvn spring-boot:run`
2. **Open Swagger UI** → navigate to `http://localhost:8080/swagger-ui.html`
3. **Expand the "IVR Session" section** to see all 5 endpoints
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
│   │   ├── api/               # REST controllers + DTOs
│   │   │   ├── AuthenticateController.java
│   │   │   ├── IvrExceptionHandler.java
│   │   │   └── dto/
│   │   ├── domain/            # Core domain model
│   │   │   ├── AuthLevel.java, TokenType.java, IvrSession.java, ...
│   │   │   └── config/        # Brand config model
│   │   ├── engine/            # Auth state machine
│   │   │   ├── AuthEngine.java
│   │   │   ├── CrossBrandTokenEvaluator.java
│   │   │   └── PromptResolver.java
│   │   ├── service/           # AuthenticateService orchestrator
│   │   ├── validator/         # Token validation layer
│   │   │   ├── TokenValidator.java (interface)
│   │   │   ├── TokenValidatorRegistry.java
│   │   │   └── impl/          # 7 stub validators
│   │   ├── registry/          # Brand config loader
│   │   ├── repository/        # SQLite session storage
│   │   └── exception/         # Custom exceptions
│   └── resources/
│       ├── application.properties
│       ├── schema.sql
│       └── brands/            # JSON brand configs
│           ├── brand-a.json
│           └── brand-b.json
```

---

## 🔧 Configuration

### Brand Configurations (JSON)

Brand rules live in `./config/brands/*.json` (external directory). Each file defines:
- **`levelRules`** — one `LevelRule` per `AuthLevel` (BASIC, STANDARD, ELEVATED, etc.)
  - **`paths`** — ordered list of token paths; [0] = primary, [1..n] = fallbacks
  - **`maxRetriesPerToken`** — attempts before path fallback or lockout
  - **`lockoutSeconds`** — lockout duration when all paths exhausted
- **`backupTokens`** — optional mapping from a required token to alternative tokens the client may submit instead (e.g. `PIN` can be replaced by `SSN_LAST4` or `DATE_OF_BIRTH`)

Add a new brand by creating a new `.json` file in the `brands/` directory and restarting.

### Application Properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session TTL in minutes |
| `ivr.session.cleanup.interval` | `60000` | Cleanup interval in ms |

---

## 📡 API Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate |
| `GET` | `/ivr/authenticate/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/authenticate/{id}` | End / hang up session |

### Example: Start a Session

```bash
curl -X POST http://localhost:8080/ivr/authenticate \
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
mvn test -Dtest=AuthEngineTest
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
- Token values stored in `collectedTokens` should be encrypted at rest (see Phase 6 in the spec).
- Session IDs are UUIDs — no sequential IDs.
- Lockout is enforced server-side; cannot be bypassed by restarting the session.
