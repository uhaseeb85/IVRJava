# IVR Token Authentication Engine

> **Multi-Brand | Progressive Auth Levels | Rule-Driven**
> Java 8 · Spring Boot 2.7.x · SQLite · OpenAPI 3.0

A production-ready engine for IVR systems that need **multi-brand authentication with progressive security levels**, **backup token alternatives**, **party disambiguation via ANI**, **customer-specific preference filtering**, and **declarative JSON-driven rules**.

This single document covers everything: what the engine does, how to run it, the full API and configuration reference, **how to onboard a new brand**, and **how to extend the engine with code** (new token types, auth levels, disambiguation rules, and real backend integrations).

---

## Table of Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Quick Start](#quick-start)
4. [API Overview](#api-overview)
5. [Configuration Reference](#configuration-reference)
6. [Onboarding a New Brand](#onboarding-a-new-brand) — *mostly configuration, no code*
7. [Extending the Engine](#extending-the-engine) — *when code is required*
8. [Project Structure](#project-structure)
9. [Testing](#testing)
10. [Tech Stack](#tech-stack)
11. [Security Considerations](#security-considerations)
12. [Further Documentation](#further-documentation)

---

## Features

- **Multi-brand isolation** — Each brand defines its own auth levels, token paths, retry limits, and fail policies
- **Progressive authentication** — Sessions start at `NONE` and step up to the target level; mid-session escalation is supported
- **Path fallbacks** — When the primary token path is exhausted, the engine automatically falls back to a configured alternative path before failing
- **Backup token alternatives** — Each required token can declare alternative token types the client may submit instead (e.g. accept `SSN_LAST4` or `DATE_OF_BIRTH` in place of `PIN`)
- **Backend verification** — A token can be bound in code to a pluggable backend **lookup service** that verifies the value against a system of record. Services are auto-discovered Spring beans; tokens are wired to them in `DefaultVerificationBindings` (no per-brand config or UI). See [`LOOKUP_SERVICE_DESIGN.md`](LOOKUP_SERVICE_DESIGN.md)
- **Party disambiguation** — When an ANI maps to multiple parties (customers), the engine applies a fixed pre-filter rule chain and requests differentiating tokens to resolve to a single party
- **Customer preference filtering** — Once a party is identified, customer-specific preferences (e.g. blocked token types) filter which tokens are offered — blocked tokens are skipped and backup alternatives or fallback paths are used instead
- **Call transfer support** — Accept calls transferred from external IVR systems with pre-validated tokens; per-source policies control which tokens and auth levels are honored
- **Optimistic locking** — Version-based concurrency control on session updates prevents lost writes under concurrent requests
- **Structured audit logging** — Auth events (token pass/fail, escalation, lockout) logged by session with caller and brand context
- **Initial tokens at session start** — Clients can submit pre-collected tokens when creating a session
- **Declarative JSON config** — All brand rules live in `./config/brands/*.json`; no code changes needed to add or modify brands
- **Brand Config Editor UI** — Web-based editor at `http://localhost:8081/` to create, view, update, and delete brand configs
- **Stateless engine** — `AuthEngine` holds no state, enabling horizontal scaling
- **Interactive API docs** — Swagger UI built in via Springdoc OpenAPI

---

## Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| REST API | Spring MVC | Accepts IVR platform calls on 3 session endpoint paths + brand CRUD |
| Auth Engine | Plain Java (Spring `@Service`) | Core state machine — evaluates rules, drives path progression |
| Rules Registry | Jackson + external JSON | Loads and caches `BrandAuthConfig` objects from `./config/brands/` |
| Transfer Policies Registry | Jackson + external JSON | Loads per-source `TransferPolicy` objects from `./config/transfers/` |
| Validator Registry | Spring bean discovery | Maps `TokenType` → `TokenValidator` implementations |
| Session Store | SQLite + JdbcTemplate | Persists `IvrSession` with full token/level/party/preference state as JSON columns; optimistic locking via version column |
| Party Lookup | Pluggable interface | Looks up parties by ANI; stub returns a single generic party |
| Disambiguation Engine | Plain Java | Applies rules, selects differentiating tokens, resolves to a single party |
| Customer Preference Provider | Pluggable interface | Loads customer preferences (blocked tokens, max level); stub returns empty |
| Brand Config API | Spring MVC + File I/O | CRUD endpoints for managing brand JSON files |
| Brand Editor UI | React + Vite + Tailwind CSS (shadcn-style) | Visual editor for brand configurations |
| API Docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI |

### How the auth flow works

```
POST /ivr/authenticate
        │
        ▼
AuthenticateController → AuthenticateService
        │
        ├── [start] PartyLookupProvider.lookupByAni()
        │       ├── 0 parties  → 400 UnknownCallerException
        │       ├── 1 party    → load prefs → AuthEngine.evaluateProgress()
        │       └── N parties  → DisambiguationEngine (token rounds) → AuthEngine
        │
        ├── [token] AuthEngine.submitToken()
        │       1. validate format → optional backend verification
        │       2. map a backup token back to the required slot
        │       3. record success, clear that slot's attempt count
        │       4. evaluateProgress() → COLLECTING / AUTHENTICATED / LOCKED
        │
        └── [escalate] AuthEngine.escalate()
                → set new targetLevel → evaluateProgress()
```

Session state is persisted in SQLite as JSON columns. `IvrSession` is a plain mutable POJO — every mutating operation ends with an explicit `sessionRepo.save(session)` (no `@Transactional`). Brand configs are JSON files in `./config/brands/*.json` mirrored by an in-memory registry.

---

## Quick Start

### Prerequisites

- [JDK 8](https://adoptium.net/temurin/releases/?version=8) (Java 1.8)
- [Maven 3.6+](https://maven.apache.org/download.cgi)
- [Node.js 18+](https://nodejs.org/) (for frontend dev only)

### Start the backend

```bash
git clone <repo-url> ivr-auth-engine
cd ivr-auth-engine
mvn spring-boot:run
```

The service starts on **`http://localhost:8081`**.

### Start the frontend (dev mode)

In a separate terminal:

```bash
cd src/main/ui
npm install       # first time only
npm run dev       # Vite dev server on :5173, proxies /api/* and /ivr/* to :8081
```

Open **`http://localhost:5173`** for hot-reload development.
To build the static files served by Spring Boot: `npm run build` (output goes to `src/main/resources/static/`, which is committed).

### Key URLs

| URL | Purpose |
|---|---|
| `http://localhost:8081/` | Brand Config Editor (production build) |
| `http://localhost:5173/` | Frontend dev server (hot reload) |
| `http://localhost:8081/swagger-ui.html` | Interactive API docs |
| `http://localhost:8081/v3/api-docs` | Raw OpenAPI JSON |

---

## API Overview

### Session endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate (discriminated by payload) |
| `GET` | `/ivr/authenticate/{id}/status` | Poll current session state |
| `DELETE` | `/ivr/authenticate/{id}` | End / hang up a session |

### Brand config endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/brands` | List all brand configs |
| `GET` | `/api/brands/{id}` | Get a brand config |
| `POST` | `/api/brands` | Create a new brand config (validates, writes file, registers live) |
| `PUT` | `/api/brands/{id}` | Update an existing brand config |
| `DELETE` | `/api/brands/{id}` | Delete a brand config |
| `POST` | `/api/brands/validate` | Dry-run validate a config without saving |

### Full auth flow example

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

### Initial tokens at session start

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

### Call transfer

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

Tokens are filtered per the source system's transfer policy (see [`config/transfers/`](config/transfers/)). The caller's `currentLevel` is capped at the policy's `maxHonoredLevel`. Attempt counts always reset.

---

## Configuration Reference

### Brand configs (JSON)

Brand configs are stored in `./config/brands/*.json` (external to the JAR). They persist across restarts and can be managed via the Brand Editor UI at `http://localhost:8081/`. The shape mirrors the Java model in [`src/main/java/com/yourco/ivr/domain/config/`](src/main/java/com/yourco/ivr/domain/config/):

```
BrandAuthConfig
├── brandId                          # unique id, e.g. "BRAND_C"
├── levelRules: Map<AuthLevel, LevelRule>
│   └── LevelRule
│       ├── paths: List<TokenPath>   # paths[0] = primary, paths[1..n] = fallbacks
│       │   └── TokenPath
│       │       ├── pathIndex        # 0-based position
│       │       ├── description      # human-readable label (shown in processing log)
│       │       ├── requiredTokens   # ordered; all must validate to complete the path
│       │       └── backupTokens     # optional: required token -> list of alternatives
│       ├── maxRetriesPerToken       # failed attempts per slot before fallback
│       ├── tokenRetryLimits         # optional per-token retry overrides
│       └── lockoutSeconds           # lockout duration after exhausting all paths
└── (disambiguation is always-on and not configurable — fixed 3-round limit +
     EXCLUDE_INACTIVE / PREFER_PRIMARY_ANI rule chain, applied to every brand)
```

| Field | Notes |
|---|---|
| `brandId` | Unique identifier (e.g. `BRAND_A`). Must match the file name. |
| `levelRules` keys | Must be valid `AuthLevel` values: `BASIC`, `STANDARD`, `ELEVATED`, `ADMIN`. Define only the levels a brand offers. |
| `paths` | Ordered list. `paths[0]` is primary; `paths[1..n]` are fallbacks activated when retries on the current path are exhausted. |
| `requiredTokens` | Ordered. The engine collects them left to right. |
| `backupTokens` | Map from a required token → alternatives the caller may submit instead. The engine accepts any of them and maps them back to the required slot internally (so the path completes without ever collecting the required token directly). The client is told what is accepted via the `acceptedTokens` response field. |
| `maxRetriesPerToken` | Default failures allowed per required-token slot before switching to the next path (or locking if all paths are exhausted). |
| `tokenRetryLimits` | *(optional)* Per-token retry overrides keyed by `TokenType`; take precedence over `maxRetriesPerToken` for that token. |
| `lockoutSeconds` | How long the session stays locked (status `REDIRECT_TO_AGENT`) after all paths at that level fail. |

#### Brand A (full example)

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
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"],
          "backupTokens": null }
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

### Built-in token types

All seven token types already have format validators, so any brand can use them with no code:

| Token type | Built-in validation rule |
|---|---|
| `ACCOUNT_NUMBER` | non-blank |
| `PIN` | length ≥ 4 |
| `OTP` | exactly 6 digits |
| `SSN_LAST4` | exactly 4 digits |
| `CARD_LAST4` | length == 4 |
| `DATE_OF_BIRTH` | ISO date `YYYY-MM-DD` |
| `VOICE_PRINT` | non-blank |

> These are **stub** validators (see [`validator/impl/`](src/main/java/com/yourco/ivr/validator/impl/)) — they check format only, not correctness against a real datastore. Good enough for development and wiring up flows; production correctness comes from a [backend verification source](#backend-verification-sources).

### Identification-only brands

Some brands don't need authentication at all — the goal is simply to **identify** which single party is calling. Set `"identificationOnly": true` on the brand config:

```json
{
  "brandId": "ID_ONLY_BRAND",
  "identificationOnly": true
}
```

In this mode:
- The flow runs party lookup (and disambiguation, if the ANI maps to multiple parties) as usual, then **stops as soon as a single party is resolved**.
- No authentication tokens are collected. `levelRules` are not required (and are ignored if present).
- The result is reported as `status: AUTHENTICATED` with **`currentLevel: NONE`** and `matchedPartyId` set to the identified party.
- **Escalation is rejected** (`400`) — there is no auth level to escalate to.

A brand-level toggle in the Brand Editor UI sets this flag, and the Dashboard renders the result as **"Identified"** rather than "Verified".

### Backend verification sources

By default a submitted token is only **format-checked**. To additionally verify a token against a real backend system of record, bind it to a **lookup service** in code via `DefaultVerificationBindings`:

```java
// src/main/java/com/yourco/ivr/lookup/DefaultVerificationBindings.java
m.put(TokenType.SSN_LAST4,
    new VerificationBinding("stub-verify", Collections.singletonMap("region", "US"), true));
```

Each binding carries:

- **`serviceId`** — id of a registered `TokenLookupService`
- **`params`** *(optional)* — params passed to the service (e.g. region/dataset). **Never store secrets here**; reference them by alias
- **`failClosed`** *(default `true`)* — when the backend is unavailable, fail the token (`true`) or fall back to format-only (`false`)

When a binding is present, the engine runs the format validator **then** calls the service; both must pass. Tokens with no binding behave exactly as before (format check only — the default for every token). Adding a backend integration is just dropping a new `@Component implements TokenLookupService`, then wiring it to a token in `DefaultVerificationBindings`. There is no per-brand config or UI for this. A configurable `stub-verify` service ships for development. Full design: [`LOOKUP_SERVICE_DESIGN.md`](LOOKUP_SERVICE_DESIGN.md).

### Party disambiguation & customer preferences

Party disambiguation is always-on for all brands. On session start, the engine calls `PartyLookupProvider.lookupByAni(callerId)` to find parties matching the ANI:

1. **0 parties** → 400 error (unknown caller)
2. **1 party** → skips disambiguation, loads `CustomerPreferenceProvider.getPreferences(partyId)`, proceeds to auth
3. **N parties** → applies the fixed pre-filter rules, then asks for differentiating tokens to resolve to a single party

Disambiguation is **not configurable** — there is no per-brand setting. The behavior is fixed in `DisambiguationEngine`: a maximum of **3** token-collection rounds, and a fixed pre-filter rule chain applied in order:
- `EXCLUDE_INACTIVE` — removes parties where `active == false`
- `PREFER_PRIMARY_ANI` — keeps parties where `primaryAni == true` (falls back to all if none are flagged)

**Customer preferences** control which tokens are offered:
- `blockedTokens` — tokens excluded from prompts; the engine tries backups or advances to the next path
- `maxAllowedLevel` — caps the maximum auth level for this customer

To integrate real backends, replace the stub implementations (see [Replacing stubs with real implementations](#replacing-stubs-with-real-implementations)).

### Application properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` | HTTP server port |
| `spring.datasource.url` | `jdbc:sqlite:ivr-auth.db` | SQLite database path |
| `ivr.session.ttl-minutes` | `30` | Session time-to-live |
| `ivr.session.cleanup.interval` | `60000` | Expired session cleanup interval (ms) |
| `ivr.brands.config-dir` | `./config/brands` | External brand config directory |
| `ivr.transfer.config-dir` | `./config/transfers` | External transfer policies directory |
| `spring.jackson.serialization.write-dates-as-timestamps` | `false` | ISO-8601 date formatting |
| `spring.jackson.time-zone` | `UTC` | Jackson time zone |

### Transfer policies (JSON)

Per-source transfer policies control which external systems can transfer calls in and what tokens/levels are honored. Edit [`config/transfers/transfer-policies.json`](config/transfers/transfer-policies.json):

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

| Field | Notes |
|---|---|
| `sourceSystemId` | Must match what the client sends as `sourceSystemId` in the transfer request |
| `honoredTokens` | Only these token types from the source system are trusted |
| `maxHonoredLevel` | The caller's claimed auth level is capped at this value, regardless of what the source reports |
| `enabled` | Set to `false` to disable a source without removing its config |

> **Transfer policies require a restart to reload.** Unlike brand configs, they are not hot-reloadable.

---

## Onboarding a New Brand

**Onboarding a brand is configuration, not code.** A brand is a single JSON file at `config/brands/{brandId}.json`. Code is only ever needed once, at the framework level, to connect real customer data — and that work is shared by every brand.

| Scenario | What it takes |
|---|---|
| **Dev / test brand** reusing the built-in token types | **Pure configuration.** One JSON file (or a few clicks in the UI). ~5 minutes. **No Java.** |
| **Production brand** reusing the built-in token types | Configuration **+** the real customer-lookup integration. That integration is written **once for the whole engine**, not per brand — if it already exists, your new brand is again pure config. |
| Brand needs a **token type that doesn't exist yet** | Small Java addition (a new `TokenValidator`). Rare. See [Extending the Engine](#extending-the-engine). |
| Brand needs **different validation** for an existing token | A small per-brand `BrandTokenValidatorOverride` bean. See [Extending the Engine](#extending-the-engine). |

### Three ways to create a brand

All three produce the same `config/brands/{brandId}.json` file and are equivalent at runtime.

#### A. Admin UI (easiest)

1. Start the app (`mvn spring-boot:run`) and open `http://localhost:8081/`.
2. Go to **Brands → New Brand**.
3. Fill in the tabs in [`BrandEditor.tsx`](src/main/ui/src/pages/BrandEditor.tsx):
   - **Rules** — pick levels, add token paths, choose required + backup tokens, set retries/lockout.
   - **Flow** — read-only visualization of the resulting paths.
   - **JSON** — read-only preview of the exact file that will be saved.
4. **Save** → issues `POST /api/brands`, validates, writes the file, and registers the brand live (no restart).

#### B. REST API

```bash
# Validate first (no write)
curl -X POST http://localhost:8081/api/brands/validate \
  -H "Content-Type: application/json" \
  -d @brand_c.json

# Then create
curl -X POST http://localhost:8081/api/brands \
  -H "Content-Type: application/json" \
  -d @brand_c.json
```

A successful create/update takes effect immediately — no restart required.

#### C. Drop a file

Place `config/brands/{brandId}.json` directly in the directory and restart the app. Brand files are loaded at startup; a file that fails to parse is logged and skipped, not fatal.

> **Filename note:** the engine derives the filename from `brandId` by lowercasing it and replacing any character outside `[a-zA-Z0-9_-]` with `_`. So `brandId: "BRAND_C"` → `brand_c.json`.

### Annotated template

Copy this, rename `brandId`, and trim to the levels you need. (JSON has no comments — strip the `// …` notes before saving.)

```jsonc
{
  "brandId": "BRAND_C",                       // unique id; filename becomes brand_c.json
  // Note: disambiguation is always-on and not configurable — no block to add here.

  "levelRules": {
    "BASIC": {                                 // simplest level: identify by account number only
      "paths": [
        {
          "pathIndex": 0,
          "description": "Account lookup",
          "requiredTokens": ["ACCOUNT_NUMBER"],
          "backupTokens": null
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    },

    "STANDARD": {                              // two paths: primary + an automatic fallback
      "paths": [
        {
          "pathIndex": 0,
          "description": "Account + PIN",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"],
          "backupTokens": {
            "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"]   // caller may offer these in place of PIN
          }
        },
        {
          "pathIndex": 1,
          "description": "Account + OTP fallback",   // used when path 0 exhausts retries
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"],
          "backupTokens": null
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 600
    }
  }
}
```

A real, minimal example already in the repo: [`config/brands/test_brand.json`](config/brands/test_brand.json).

### What validation enforces

`POST`/`PUT` and the `/validate` endpoint run `BrandService.validate()`. A config is rejected (HTTP 400) unless **all** of these hold:

1. `brandId` is present and non-blank.
2. `levelRules` has at least one level.
3. Every level has at least one `TokenPath`.
4. Every path has at least one entry in `requiredTokens`.

That's the full ruleset — everything else (retries, lockout, backups, disambiguation) is optional and defaulted.

### Onboarding checklist

1. **Gather requirements** — which auth levels does this brand offer, and which tokens prove each level?
2. **Confirm token coverage** — every token you reference must be one of the seven [built-in types](#built-in-token-types). If you need a new one, see [Extending the Engine](#extending-the-engine).
3. **Draft the config** — start from the template above or copy an existing file in `config/brands/`.
4. **Validate** — `POST /api/brands/validate` (or rely on the UI, which validates on save).
5. **Create** — UI **New Brand**, `POST /api/brands`, or drop-file + restart.
6. **Smoke test** — run a full session against the brand (below).
7. **Commit the file** — `config/brands/{brandId}.json` is checked into the repo; commit it so the brand exists in every environment.

### Smoke test

With the brand created and the server running on `:8081`, drive a full session through `POST /ivr/authenticate`. Test values just need to satisfy the format rules (the stub party lookup accepts any caller).

```bash
# 0. Confirm the brand persisted
curl http://localhost:8081/api/brands/BRAND_C

# 1. Start a session aiming for STANDARD -> response carries a sessionId + next required token
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"brandId":"BRAND_C","callerId":"5551234567","targetLevel":"STANDARD"}'

# 2. Submit the account number (use the sessionId from step 1)
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","tokenType":"ACCOUNT_NUMBER","tokenValue":"123456789"}'

# 3. Submit a PIN (length >= 4) -> expect status AUTHENTICATED at STANDARD
curl -X POST http://localhost:8081/ivr/authenticate \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","tokenType":"PIN","tokenValue":"1234"}'
```

You can also run this end-to-end from the **Dashboard** test console in the UI, or explore the API interactively at `http://localhost:8081/swagger-ui.html`.

---

## Extending the Engine

Most maintenance is pure configuration. This section covers the cases that **do** require code — adding token types, auth levels, disambiguation rules, brand-specific validators, and replacing the stub integrations.

### What needs code vs. what doesn't

| Task | Code change? | How |
|---|---|---|
| Add a new brand | ❌ No | JSON file, UI, or API |
| Change a brand's token paths / retries / lockout | ❌ No | Edit JSON file or use UI |
| Add a transfer policy | ❌ No | Edit `config/transfers/transfer-policies.json` (restart) |
| Add a new token type | ✅ Yes | Enum + validator + prompt text |
| Add a new auth level | ✅ Yes | Enum rank + update brand configs |
| Add a disambiguation rule type | ✅ Yes | Implement `DisambiguationRule` + register in engine |
| Brand-specific validation logic | ✅ Yes | `BrandTokenValidatorOverride` Spring bean |
| Verify a token against a real backend | ✅ Yes | `TokenLookupService` + `DefaultVerificationBindings` (see [Backend verification sources](#backend-verification-sources)) |
| Replace stub party lookup / preferences | ✅ Yes | Implement the interface + remove `@Component` from the stub |

> Per project rule, **any new validator or engine behavior change needs a unit/integration test** under `src/test/java/com/yourco/ivr/`.

### Adding a new token type

Four code changes; no other files are affected.

**Step 1 — Add to the `TokenType` enum** (`domain/TokenType.java`):

```java
public enum TokenType {
    ACCOUNT_NUMBER, PIN, OTP, SSN_LAST4, VOICE_PRINT, DATE_OF_BIRTH, CARD_LAST4,
    SECURITY_QUESTION   // ← add here
}
```

**Step 2 — Create a validator** (`validator/impl/SecurityQuestionValidator.java`):

```java
package com.yourco.ivr.validator.impl;

import com.yourco.ivr.domain.TokenType;
import com.yourco.ivr.validator.TokenValidationContext;
import com.yourco.ivr.validator.TokenValidator;
import com.yourco.ivr.validator.ValidationErrorCode;
import com.yourco.ivr.validator.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class SecurityQuestionValidator implements TokenValidator {

    @Override
    public TokenType supportedType() {
        return TokenType.SECURITY_QUESTION;
    }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        // TODO: replace stub logic with a real call to your answer-verification service
        String answer = ctx.getTokenValue();
        if (answer != null && !answer.isBlank()) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(ValidationErrorCode.INVALID);
    }
}
```

`@Component` is all that's needed — `TokenValidatorRegistry` auto-discovers all `TokenValidator` beans on startup.

**Step 3 — Add a human-readable prompt name** in `engine/PromptResolver.java` (`tokenName()`):

```java
case SECURITY_QUESTION: return "security question answer";
```

**Step 4 — (Only if the token can identify a party)** add it to `DisambiguationEngine`:

```java
// in defaultTokenFieldMap():
map.put(TokenType.SECURITY_QUESTION, Party::getSecurityAnswer);

// and in formatTokenName():
case SECURITY_QUESTION: return "security question answer";
```

If the token is only used for auth (not disambiguation), skip Step 4. Then reference it in brand configs: `"requiredTokens": ["ACCOUNT_NUMBER", "SECURITY_QUESTION"]`. A restart is required for the enum + validator code changes.

### Adding a new auth level

Auth levels have a fixed rank order. Adding one touches two places.

**Step 1 — Add to the `AuthLevel` enum** (`domain/AuthLevel.java`):

```java
public enum AuthLevel {
    NONE(0), BASIC(1), STANDARD(2), ELEVATED(3), ADMIN(4),
    SUPER_ADMIN(5);   // ← add with the next rank
    ...
}
```

Ranks must be unique and ordered correctly — `isHigherThan()` compares them numerically.

**Step 2 — Add rules to the brands that need it.** Add a `SUPER_ADMIN` entry to `levelRules` in whichever brand configs require it. Brands that don't define a level simply cannot be escalated to it. No other code changes needed.

### Adding a new disambiguation rule

Disambiguation rules filter the candidate party list before token-based resolution begins.

**Step 1 — Implement the interface** (`engine/impl/PreferBusinessAccountRule.java`):

```java
package com.yourco.ivr.engine.impl;

import com.yourco.ivr.domain.Party;
import com.yourco.ivr.engine.DisambiguationRule;

import java.util.List;
import java.util.stream.Collectors;

public class PreferBusinessAccountRule implements DisambiguationRule {

    @Override
    public List<Party> apply(List<Party> parties) {
        List<Party> business = parties.stream()
            .filter(Party::isBusinessAccount)
            .collect(Collectors.toList());
        // Only filter if it doesn't eliminate everyone
        return business.isEmpty() ? parties : business;
    }
}
```

Do **not** annotate with `@Component` — rules are instantiated directly by the engine.

**Step 2 — Add it to the fixed rule chain** in the `DisambiguationEngine` constructor (broader filters first):

```java
this.rules = Arrays.asList(
    new ExcludeInactiveRule(),
    new PrimaryAniRule(),
    new PreferBusinessAccountRule());
```

The rule chain is hardcoded and not configurable per brand — the new rule applies to **every** brand.

### Brand-specific validator overrides

Sometimes one brand needs stricter or different validation for a token type (e.g. BRAND_B validates PINs against a different backend). Use `BrandTokenValidatorOverride`:

```java
package com.yourco.ivr.validator;

import com.yourco.ivr.domain.TokenType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidatorOverrideConfig {

    @Bean
    public BrandTokenValidatorOverride brandBPinOverride() {
        return new BrandTokenValidatorOverride("BRAND_B", new TokenValidator() {
            @Override
            public TokenType supportedType() { return TokenType.PIN; }

            @Override
            public ValidationResult validate(TokenValidationContext ctx) {
                boolean ok = myBackend.verifyPin(ctx.getCallerId(), ctx.getTokenValue());
                return ok ? ValidationResult.ok()
                          : ValidationResult.fail(ValidationErrorCode.INVALID);
            }
        });
    }
}
```

`TokenValidatorRegistry` automatically picks up all `BrandTokenValidatorOverride` beans. When a session belongs to `BRAND_B` and submits `PIN`, the override is used; all other brands and token types still use the default validators.

### Replacing stubs with real implementations

The engine ships with two stub implementations that always return fixed data. Replace them before going to production.

**`PartyLookupProvider` — looks up parties by ANI:**

```java
@Component
@Primary   // or remove @Component from StubPartyLookupProvider
public class CrmPartyLookupProvider implements PartyLookupProvider {

    private final CrmClient crm;

    public CrmPartyLookupProvider(CrmClient crm) {
        this.crm = crm;
    }

    @Override
    public List<Party> lookupByAni(String callerId) {
        return crm.findPartiesByPhoneNumber(callerId)
            .stream()
            .map(this::toParty)
            .collect(Collectors.toList());
    }
    ...
}
```

The engine handles the three outcomes automatically: **0 parties** → 400 `UNKNOWN_CALLER`; **1 party** → proceeds to auth; **N parties** → enters disambiguation.

**`CustomerPreferenceProvider` — loads per-customer token preferences:**

```java
@Component
@Primary
public class DbCustomerPreferenceProvider implements CustomerPreferenceProvider {

    @Override
    public CustomerPreference getPreferences(String partyId, String brandId) {
        CustomerPreference prefs = new CustomerPreference();
        prefs.setBlockedTokens(db.getBlockedTokens(partyId, brandId));
        prefs.setMaxAllowedLevel(db.getMaxLevel(partyId, brandId));
        return prefs;
    }
}
```

`blockedTokens` — the engine will skip these token types and try backup alternatives or the next fallback path automatically.

### Stub data (for tests)

`StubPartyLookupProvider` returns one party per `callerId` with `partyId = "STUB-<callerId>"`, `accountNumber = callerId`, `active = true`, `primaryAni = true`. `StubCustomerPreferenceProvider` returns empty preferences (no blocked tokens, no level cap). Override these in tests using `@MockBean` when you need specific scenarios.

---

## Project Structure

```
src/main/java/com/yourco/ivr/
├── api/                    # REST layer
│   ├── AuthenticateController.java  # Unified session endpoints (3 total)
│   ├── BrandController.java         # Brand CRUD endpoints
│   ├── IvrExceptionHandler.java     # Global error handler
│   ├── LookupServiceController.java # Lookup service discovery API
│   └── dto/                         # Request/Response DTOs
├── domain/                 # Core domain model
│   ├── AuthLevel.java              # Auth level enum with rank
│   ├── TokenType.java              # 7 token types
│   ├── SessionPhase.java           # DISAMBIGUATION / AUTHENTICATING
│   ├── IvrSession.java             # Full session state (versioned)
│   ├── SessionStatus.java          # Session lifecycle states
│   ├── Party.java                  # Customer party record
│   ├── CustomerPreference.java     # Blocked tokens, max level caps
│   ├── ValidationResult.java       # Generic validation result
│   └── config/                     # Brand config model + transfer policy
│       ├── BrandAuthConfig.java
│       ├── LevelRule.java
│       ├── TokenPath.java
│       ├── TransferPolicy.java
│       └── TransferPoliciesConfig.java
├── engine/                 # Auth state machine
│   ├── AuthEngine.java             # Core engine (disambig routing + pref filtering + two-stage validation)
│   ├── DisambiguationEngine.java   # Party resolution + token matching
│   ├── DisambiguationRule.java     # Rule interface
│   ├── PromptResolver.java
│   └── impl/
│       ├── ExcludeInactiveRule.java
│       └── PrimaryAniRule.java
├── lookup/                 # Backend token verification
│   ├── TokenLookupService.java     # SPI — pluggable backend verifier
│   ├── LookupServiceRegistry.java  # Auto-built registry of all services
│   ├── VerificationBindings.java   # In-code token→service binding interface
│   ├── DefaultVerificationBindings.java  # Default impl
│   ├── VerificationBinding.java    # Binding config (serviceId, params, failClosed)
│   ├── LookupRequest.java / LookupResult.java
│   └── impl/StubLookupService.java # Configurable dev stub
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
│   ├── TokenValidator.java         # Interface (format checks)
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
├── exception/              # Custom exceptions (mapped to HTTP status by the handler)
├── IvrAuthEngineApplication.java
└── OpenApiConfig.java

src/main/resources/
├── application.properties
├── schema.sql
└── static/index.html        # Brand Config Editor SPA (built from src/main/ui/)

config/brands/               # External brand config directory (loaded at startup)
├── brand_a.json              # BRAND_A — full example with 3 levels, backup tokens
├── brand_b.json              # BRAND_B — simpler config with 2 levels
├── id_only_brand.json        # ID_ONLY_BRAND — identification-only mode
└── test_brand.json           # TEST_BRAND — test brand with 2 levels

config/transfers/             # External transfer policy directory
└── transfer-policies.json    # Per-source token/level policies

src/test/java/com/yourco/ivr/
├── IvrAuthIntegrationTest.java                 # Auth, transfer, backup, fallback
├── DisambiguationAndPreferenceTest.java        # Disambiguation + preferences (uses MockBean)
├── BackendVerificationIntegrationTest.java     # Backend verification pipeline
└── IdentificationOnlyIntegrationTest.java      # Identification-only brand mode
```

---

## Testing

**Every new feature or engine behavior change requires a new test.** Tests live in `src/test/java/com/yourco/ivr/` and are `@SpringBootTest(webEnvironment = RANDOM_PORT)` integration tests using `TestRestTemplate`. The SQLite DB is created fresh per test run.

```bash
mvn test                          # run all tests
mvn test -Dtest=IvrAuthIntegrationTest    # run a specific class
```

**What to test for new tokens:**
- Happy path — submit the new token, session reaches `AUTHENTICATED`
- Failure path — submit wrong value N times, verify lockout or path switch
- Backup path — if the token is a backup for another, verify it's accepted

**What to test for new brands:**
- Start a session, verify the correct `nextRequiredToken` is returned
- Complete the primary path, verify `AUTHENTICATED`
- Exhaust retries, verify the fallback path activates

---

## Tech Stack

| Component | Choice | Rationale |
|---|---|---|
| Language | Java 8 | Enterprise compatibility |
| Framework | Spring Boot 2.7.18 | Last Spring Boot version supporting Java 8 (uses `javax.*`, not `jakarta.*`) |
| Database | SQLite (via JdbcTemplate) | Zero-config embedded database |
| Config format | JSON (via Jackson) | Portable, well-supported by Spring Boot |
| API docs | Springdoc OpenAPI 1.7 | Auto-generates Swagger UI from annotations |
| Build | Maven | Industry standard for enterprise Java |
| Code gen | Lombok | Reduces boilerplate |
| Frontend | React 19, Vite, TypeScript, Tailwind CSS 3 | Minimal-dependency admin UI |
| Testing | JUnit 5 + SpringBootTest | Integration tests with real HTTP calls |

---

## Security Considerations

- **Never log raw token values** — log only `tokenType` and validation outcome (`PASS` / `FAIL`)
- Raw token values (PINs, SSNs, account numbers) are never persisted to the database — the `collected_tokens` column is always null; values exist in memory only for the duration of a single request
- Session IDs are UUIDs — no sequential enumeration possible
- Lockout is enforced server-side and cannot be bypassed
- Use HTTPS in production — token values are submitted via the API

---

## Further Documentation

- **[Technical Spec](IVR_Auth_Engine_Technical_Spec.md)** — Full system design document (must stay in sync with code changes)
- **[Lookup Service Design](LOOKUP_SERVICE_DESIGN.md)** — Backend token verification architecture
- **[GitHub Guide](.github/github-instructions.md)** — Contribution workflow, branching strategy, and PR checklist
- **[Swagger UI](http://localhost:8081/swagger-ui.html)** — Interactive API documentation (run the service first)
- **[Brand Config Editor](http://localhost:8081/)** — Web UI for managing brand configurations

> Per project rule, update `README.md` and the Technical Spec whenever endpoints, brand config structure, or engine behavior changes.

---

## License

Proprietary — Internal Use
