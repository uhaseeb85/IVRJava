# CLAUDE.md — IVR Token Authentication Engine

This file is read by Claude Code at the start of every session. Keep it up to date when architecture or conventions change.

---

## Project Overview

Multi-brand IVR authentication engine. A Spring Boot 2.7.x backend exposes a unified REST endpoint that drives a state machine through party disambiguation → token collection → progressive auth levels. A React/Vite admin UI manages brand configuration files.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 8 (source/target `1.8`) |
| Framework | Spring Boot **2.7.18** (uses `javax.*` namespace — NOT `jakarta.*`) |
| Database | SQLite via `JdbcTemplate` (no ORM, no `@Transactional`) |
| Code gen | Lombok (`@Data`, `@Builder`, `@Slf4j`) |
| API docs | Springdoc OpenAPI 1.7 (Swagger UI) |
| Build | Maven 3.6+ |
| Frontend | React 19, Vite 8, TypeScript, Tailwind CSS 3, lucide-react |

---

## Essential Commands

### Backend

```bash
# Compile only (fast sanity check)
mvn clean compile

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=AuthEngineTest

# Start the server (port 8081)
mvn spring-boot:run
```

### Frontend (`src/main/ui/`)

```bash
npm install            # first time only
npm run dev            # Vite dev server on :5173, proxies /api/* and /ivr/* to :8081
npm run build          # compiles → src/main/resources/static/ (checked in)
```

> **Always run `npm run build` before committing UI changes.** The built static files in `src/main/resources/static/` are what the Spring Boot app serves.

---

## Key URLs (when running)

| URL | Purpose |
|---|---|
| `http://localhost:8081/` | Brand Config Editor (production build) |
| `http://localhost:8081/swagger-ui.html` | Interactive API docs |
| `http://localhost:8081/v3/api-docs` | Raw OpenAPI JSON |
| `http://localhost:5173/` | Frontend dev server (hot reload) |

---

## Project Structure

```
src/main/java/com/yourco/ivr/
├── api/                    # REST controllers + DTOs + global exception handler
├── domain/                 # Core model: IvrSession, AuthLevel, TokenType, Party, ...
│   └── config/             # Brand config model: BrandAuthConfig, LevelRule, TokenPath, ...
├── engine/                 # AuthEngine, DisambiguationEngine, CrossBrandTokenEvaluator
├── service/                # AuthenticateService (orchestrator), BrandService (file CRUD)
├── registry/               # BrandRulesRegistry, TransferPoliciesRegistry (in-memory caches)
├── repository/             # SqliteSessionRepository (INSERT OR REPLACE, JSON columns)
├── validator/              # TokenValidator interface + 7 stub implementations
├── partylookup/            # PartyLookupProvider interface + StubPartyLookupProvider
├── preference/             # CustomerPreferenceProvider interface + stub
└── exception/              # Custom RuntimeException subclasses

src/main/ui/src/            # React frontend
├── App.tsx                 # Layout, routing (page state), collapsible sidebar
├── pages/Dashboard.tsx     # Test console (session start/token submit/escalate)
├── pages/Brands.tsx        # Brand card grid + search
├── pages/BrandEditor.tsx   # Rules/Disambiguation/Flow/JSON tabs
├── pages/SessionLog.tsx    # localStorage-backed session history
└── lib/sessions.ts         # Session history persistence (localStorage, 200-entry cap)

config/brands/              # External brand JSON files (loaded at startup, managed via UI)
config/transfers/           # Transfer policy JSON files
```

---

## Architecture: How the Auth Flow Works

```
POST /ivr/authenticate
        │
        ▼
AuthenticateController → AuthenticateService
        │
        ├── [start] PartyLookupProvider.lookupByAni()
        │       ├── 0 parties  → 400 UnknownCallerException
        │       ├── 1 party    → load prefs → AuthEngine.evaluateProgress()
        │       └── N parties  → DisambiguationEngine.start() → (token rounds) → AuthEngine
        │
        ├── [token] AuthEngine.submitToken()
        │       1. validateExternally() via TokenValidatorRegistry
        │       2. resolveBackupToken() — map alt token → required token
        │       3. add to validatedTokens, clear attemptCount
        │       4. evaluateProgress() → COLLECTING / AUTHENTICATED / LOCKED
        │
        └── [escalate] AuthEngine.escalate()
                → set new targetLevel → evaluateProgress()
```

**Session state** is persisted in SQLite as JSON columns. `IvrSession` is a plain mutable POJO — no entity annotations. Every mutating operation ends with `sessionRepo.save(session)`.

**Brand configs** are JSON files in `./config/brands/*.json`. File name is `{brandId.toLowerCase()}.json`. The registry is an in-memory `ConcurrentHashMap<String, BrandAuthConfig>` that mirrors the files.

---

## Coding Conventions

- **No `@Transactional`** — SQLite single-file DB; all saves are explicit `sessionRepo.save()` calls.
- **Lombok `@Data`** on config/domain classes — setters are generated. Don't add manual getters/setters unless you need custom logic.
- **`@Builder`** on `AuthenticateResponse` — always use the builder, never a constructor.
- **Enums over strings** — `AuthLevel`, `TokenType`, `SessionStatus`, `SessionPhase` are enums. Don't pass their `.name()` string through business logic; compare the enum directly.
- **`javax.annotation`** (not `jakarta`) — This is Spring Boot 2.7.x. Use `javax.annotation.PostConstruct`, `javax.validation.constraints.*`, etc.
- **No raw `RuntimeException` wraps** — throw a specific custom exception from `com.yourco.ivr.exception.*` so the global handler maps it to the right HTTP status.
- **Constructor injection** — Spring Boot 2.7+ auto-detects single-constructor injection; `@Autowired` on constructors is redundant.

---

## Rules That Must Never Be Broken

1. **Never log raw token values** (PINs, OTPs, SSN digits, account numbers). Log only `tokenType` and validation outcome (`PASS` / `FAIL`).
2. **Never store sensitive token values in plain text** in logs, audit events, or error messages.
3. **New unit/integration tests are required for every new feature or engine behavior change.** Tests live in `src/test/java/com/yourco/ivr/`.
4. **Keep docs in sync** — `README.md` and `IVR_Auth_Engine_Technical_Spec.md` must be updated when endpoints, config structure, or engine behavior changes.
5. **Validate brand configs** before saving via `BrandService.validate()`. Never bypass this by calling `registry.register()` directly from outside the service.

---

## Known Issues / Tech Debt (from code review)

These are open problems to be aware of when touching related code:

| Issue | Location | Notes |
|---|---|---|
| **Session write race condition** | `SqliteSessionRepository`, `AuthEngine` | No optimistic locking. Concurrent token submissions for the same session → last write wins. Fix: add a `version` column, use `UPDATE … WHERE version = ?`. |
| **`SessionSerializationException` unhandled** | `IvrExceptionHandler` | Falls through to 500 with stack trace. Add a handler. |
| **No catch-all exception handler** | `IvrExceptionHandler` | Unexpected exceptions expose stack traces. Add `@ExceptionHandler(Exception.class)`. |
| **Registry gap during refresh** | `BrandService.refreshRegistry()` | `clear()` then `loadFromDirectory()` leaves a window where all brands are gone. Fix with atomic swap. |
| **`file.delete()` return not checked** | `BrandService.delete()` | Silent failure on permission errors. |
| **Double registry lookup in transfer** | `AuthenticateService.transfer()` | `transferRegistry.get()` called twice; use a local variable. |
| **No session ownership check** | `AuthEngine`, `AuthenticateService` | Any caller who knows a `sessionId` can submit tokens against it. |
| **Token pruning loses cross-path tokens** | `AuthEngine.pruneTokensNotInPath()` | Tokens validated in path N are discarded when switching to path N+1, even if they're required there. |
| **Locked sessions not enforced at entry** | `AuthEngine.submitToken()` | Check `LOCKED` status before processing; respect `lockedUntil` expiry. |
| **Two DB queries per session read** | `SqliteSessionRepository.getOrThrow()` | `checkExpired()` + main `SELECT` can be merged into one query. |
| **No audit logging** | `AuthEngine`, `AuthenticateService` | Zero auth events logged. Add structured log entries for start/token/escalate/auth outcomes. |

---

## Testing

Tests are integration tests using `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `TestRestTemplate`. The SQLite DB is created fresh per test run (in-memory via `spring.datasource.url=jdbc:sqlite::memory:` injected by test config, or the file DB if not overridden).

```bash
mvn test                          # run everything
mvn test -Dtest=AuthEngineTest    # single class
```

The stub implementations (`StubPartyLookupProvider`, `StubCustomerPreferenceProvider`) return hardcoded data — see these stubs to understand what test scenarios assume about parties and preferences.

---

## External Configuration (runtime)

Files loaded at startup from these directories (created automatically if missing):

| Directory | Content |
|---|---|
| `./config/brands/` | `{brandId}.json` — one file per brand |
| `./config/transfers/` | `transfer-policies.json` — all transfer policies in one file |

Changes to brand files can be picked up at runtime via `PUT /api/brands/{id}` (which calls `BrandService.save()` and refreshes the registry). Transfer policies require a restart to reload.

---

## Frontend Notes

- **No new npm packages** unless clearly necessary — the UI intentionally has minimal dependencies (React, Tailwind, lucide-react, tailwind-merge, clsx).
- **Session history** is stored in `localStorage` under key `ivr_sessions_v1`, capped at 200 entries. It's written by `Dashboard.tsx` and read by `SessionLog.tsx` via `src/lib/sessions.ts`.
- **The `_existing` flag** on `BrandConfig` in `BrandEditor.tsx` is a frontend-only marker (stripped before saving) that controls whether `POST` or `PUT` is used. It is never sent to the backend.
- **Build output** goes to `src/main/resources/static/` via `vite.config.ts`. This directory is committed and served by Spring Boot's static resource handler.
