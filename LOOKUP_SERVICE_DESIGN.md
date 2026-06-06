# Design: Configurable Token Lookup / Verification Services

Status: **Implemented** · Scope: backend + frontend · Tests: `LookupServiceIntegrationTest` (6 tests, green)

> This document is the original design. The feature has since been built per the plan in §12.
> Brand config field: `verificationSources`. Discovery API: `GET /api/lookup-services`.
> Stub service id: `stub-verify`. UI: Brand Editor → **Verification** tab.

---

## 1. Context & goal

Today a token is checked only for **format** — e.g. `SsnLast4Validator` confirms "4 digits", nothing more. There is no way to verify a token against a **real backend system of record** (e.g. "does this SSN match the customer record in Experian / Core Banking?"), and no way for a brand administrator to choose, per token, *which* backend performs that verification.

**Goal:** Let an administrator, in the Brand Editor UI, bind a token (e.g. `SSN_LAST4`) to a named **lookup service** chosen from a registry of available services. Services are pluggable backend components ("drop a new `@Component`"); brands wire them up **on demand via config**, with no per-brand code.

**Decisions taken** (driving this doc):
- **Granularity:** brand-level, per token. A token is verified the same way everywhere in the brand.
- **Initial scope:** the pluggable *framework* + one configurable **stub** service. Real HTTP-calling services and secret resolution are deferred (see §11).
- **Deliverable:** this design/plan. No code yet.

---

## 2. Core idea: separate *format validation* from *backend verification*

These are two distinct concerns and stay as two layers:

| Layer | Exists today? | Runs when | Cost |
|---|---|---|---|
| **Format validation** (`TokenValidator`) | Yes | Always | Cheap, no network |
| **Backend verification** (`TokenLookupService`) | **New** | Only if the brand config binds the token to a service | Network call |

The binding *token → service* lives in **brand config**, not in code. That is the entire "wire it up on demand" requirement.

### The seam we hook into

`AuthEngine.validateExternally()` ([`AuthEngine.java:611`](src/main/java/com/yourco/ivr/engine/AuthEngine.java)) is the single choke point through which every token submission flows:

```java
private ValidationResult validateExternally(IvrSession session, TokenType tokenType, String tokenValue) {
    TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
    TokenValidationContext ctx = new TokenValidationContext(...);
    return validator.validate(ctx);
}
```

All retry / path-fallback / audit-logging logic downstream keys off the returned `ValidationResult`. If we make this method return the *combined* result of (format gate + optional backend verification), **nothing downstream changes**.

---

## 3. Backend component design

### 3.1 The Lookup Service SPI

New package `com.yourco.ivr.lookup`.

```java
public interface TokenLookupService {
    String id();                         // stable registry key, e.g. "experian-ssn"
    String displayName();                // UI label, e.g. "Experian SSN Verify"
    String description();                // UI helptext
    Set<TokenType> supportedTokens();    // UI filters available services by this
    LookupResult verify(LookupRequest req);
}
```

```java
public final class LookupRequest {          // immutable
    private final TokenType tokenType;
    private final String tokenValue;         // NEVER logged
    private final String callerId;
    private final String brandId;
    private final Map<TokenType, String> sessionTokens;  // already-collected tokens (e.g. account #) usable as lookup key
    private final Map<String, String> params;            // per-brand binding params (optional)
}

public final class LookupResult {
    private final boolean verified;
    private final ValidationErrorCode code;  // null when verified
    private final String detail;             // safe, non-sensitive
    public static LookupResult ok();
    public static LookupResult fail(ValidationErrorCode code, String detail);
}
```

### 3.2 The registry (auto-built from Spring beans)

```java
@Component
public class LookupServiceRegistry {
    private final Map<String, TokenLookupService> byId;

    public LookupServiceRegistry(List<TokenLookupService> services) {
        // index by id(); fail fast on duplicate ids
    }
    public TokenLookupService get(String id);                 // throws UnknownLookupServiceException
    public Collection<TokenLookupService> all();              // for discovery API
    public List<TokenLookupService> forToken(TokenType t);    // services whose supportedTokens contains t
}
```

This mirrors the existing [`TokenValidatorRegistry`](src/main/java/com/yourco/ivr/validator/TokenValidatorRegistry.java), which Spring already builds by injecting `List<TokenValidator>`. **Adding a backend = one new `@Component implements TokenLookupService`.** No other wiring.

### 3.3 The stub service (ships now, for dev/test)

```java
@Component
public class StubLookupService implements TokenLookupService {
    public String id() { return "stub-verify"; }
    public String displayName() { return "Stub Verifier (always pass)"; }
    public Set<TokenType> supportedTokens() { return EnumSet.allOf(TokenType.class); }
    public LookupResult verify(LookupRequest req) {
        // configurable pass/fail via params, default PASS; mirrors the existing stub providers
        return LookupResult.ok();
    }
}
```

This is the analogue of `StubPartyLookupProvider` / `StubCustomerPreferenceProvider` — lets the whole feature be exercised end-to-end before any real integration exists.

---

## 4. Config model (what the UI edits)

### 4.1 New field on `BrandAuthConfig`

[`BrandAuthConfig`](src/main/java/com/yourco/ivr/domain/config/BrandAuthConfig.java) gains one **optional** map:

```java
private Map<TokenType, VerificationBinding> verificationSources;   // null ⇒ behave exactly as today
```

```java
@Data
public class VerificationBinding {
    private String serviceId;             // must exist in LookupServiceRegistry
    private Map<String, String> params;   // optional per-brand params (reserved; see §11)
    private boolean failClosed = true;    // backend unavailable ⇒ FAIL (true) or skip verification (false)
}
```

A small null-safe accessor (like the existing `getDisambiguation()`):

```java
public VerificationBinding verificationSourceFor(TokenType t) {
    return verificationSources == null ? null : verificationSources.get(t);
}
```

### 4.2 Resulting brand JSON

```jsonc
{
  "brandId": "BRAND_C",
  "verificationSources": {
    "SSN_LAST4":      { "serviceId": "stub-verify", "params": { "region": "US" } },
    "ACCOUNT_NUMBER": { "serviceId": "stub-verify" }
  },
  "levelRules": { "...": "unchanged" }
}
```

### 4.3 Backward compatibility

`verificationSources` is optional and additive. **Every existing brand file deserializes unchanged** and behaves exactly as today (format-only). No migration required. The existing `BrandTokenValidatorOverride` mechanism is untouched.

---

## 5. Engine wiring (one method, two stages)

`validateExternally()` becomes a pipeline. `AuthEngine` gains a `LookupServiceRegistry` constructor dependency, and the method receives the already-loaded `config` (in scope at the call site, [`AuthEngine.java:160`](src/main/java/com/yourco/ivr/engine/AuthEngine.java)):

```java
private ValidationResult validateExternally(IvrSession session, BrandAuthConfig config,
                                            TokenType tokenType, String tokenValue) {
    // Stage 1 — format gate (existing). Cheap; rejects malformed input before any network call.
    ValidationResult fmt = validatorRegistry.resolve(session.getBrandId(), tokenType)
                                            .validate(buildCtx(...));
    if (!fmt.isValid()) return fmt;

    // Stage 2 — backend verification, only if this token is bound to a service.
    VerificationBinding binding = config.verificationSourceFor(tokenType);
    if (binding == null) return ValidationResult.ok();        // unchanged legacy behavior

    try {
        TokenLookupService svc = lookupRegistry.get(binding.getServiceId());
        LookupResult r = svc.verify(new LookupRequest(
            tokenType, tokenValue, session.getCallerId(),
            session.getBrandId(), session.getCollectedTokens(), binding.getParams()));
        return r.isVerified()
            ? ValidationResult.ok()
            : ValidationResult.fail(r.getCode() != null ? r.getCode() : ValidationErrorCode.VERIFICATION_FAILED);
    } catch (RuntimeException ex) {                            // backend down / timeout
        log.warn("LOOKUP brand={} token={} service={} UNAVAILABLE", session.getBrandId(),
                 tokenType, binding.getServiceId());           // no token value logged
        return binding.isFailClosed()
            ? ValidationResult.fail(ValidationErrorCode.VERIFICATION_UNAVAILABLE)
            : ValidationResult.ok();
    }
}
```

New `ValidationErrorCode` values: `VERIFICATION_FAILED`, `VERIFICATION_UNAVAILABLE` (add to [`ValidationErrorCode`](src/main/java/com/yourco/ivr/validator/ValidationErrorCode.java)).

Downstream PASS/FAIL handling, retries, path fallback, and the existing audit log line ([`AuthEngine.java:169`](src/main/java/com/yourco/ivr/engine/AuthEngine.java)) require **no changes**.

---

## 6. Discovery API (populates the UI dropdown)

New `LookupServiceController` (package `com.yourco.ivr.api`):

```
GET /api/lookup-services
→ 200 [
    { "id": "stub-verify",
      "displayName": "Stub Verifier (always pass)",
      "description": "...",
      "supportedTokens": ["ACCOUNT_NUMBER","PIN","OTP","SSN_LAST4","CARD_LAST4","DATE_OF_BIRTH","VOICE_PRINT"],
      "paramSchema": [ { "key": "region", "required": false } ]   // optional, for param inputs
    }
  ]
```

Backed by `LookupServiceRegistry.all()`, mapped to a `LookupServiceDescriptor` DTO. Read-only; no auth changes. (`paramSchema` is optional metadata a service may expose; with stub-only scope it can be empty.)

---

## 7. Save-time validation

Extend [`BrandService.validate()`](src/main/java/com/yourco/ivr/service/BrandService.java) (inject `LookupServiceRegistry`). For each `verificationSources` entry:

1. `serviceId` is non-blank.
2. The service exists in the registry.
3. The service's `supportedTokens()` contains the bound `TokenType`.

Any violation → `ValidationResult.error(...)` → HTTP 400 on `POST/PUT /api/brands` and on `POST /api/brands/validate`. Fails fast at save time, before the binding is ever exercised on a live call.

---

## 8. Frontend (Brand Editor)

In [`BrandEditor.tsx`](src/main/ui/src/pages/BrandEditor.tsx):

- On load, fetch `GET /api/lookup-services` once.
- Add a **"Verification Sources"** section (brand-level, matching the chosen granularity). For each `TokenType` the brand uses, render a dropdown of services whose `supportedTokens` include that token, plus optional param inputs from `paramSchema`. "None" is a valid choice (= format-only, today's behavior).
- Serialize selections into `verificationSources` on save (`POST`/`PUT /api/brands`). The existing **JSON tab** then shows the binding automatically.
- Add TS types (`LookupServiceDescriptor`, `VerificationBinding`) and extend the brand config type. **No new npm dependencies.**
- Run `npm run build` before committing (built output in `src/main/resources/static/` is what Spring serves).

---

## 9. Cross-cutting concerns

- **No raw token logging** (project rule #1): `LookupRequest.tokenValue` is never logged by services or the engine; audit/log lines carry `serviceId`, `tokenType`, and outcome only.
- **Secrets** (rule #2): API keys must **not** live in brand JSON. `params` may carry a *secret alias*; real credential resolution from env/secret store is deferred (§11).
- **Latency / resilience:** lookups are synchronous within a live IVR turn. Each real service should enforce its own timeout; the engine treats any thrown exception via the `failClosed` policy. Optional future: per-session result cache so re-prompts don't re-hit the backend.
- **Fail policy:** default `failClosed = true` (unavailable ⇒ FAIL). A brand may opt into graceful degradation per token.

---

## 10. File-by-file change list

**New**
| File | Purpose |
|---|---|
| `lookup/TokenLookupService.java` | SPI |
| `lookup/LookupRequest.java`, `lookup/LookupResult.java` | request/result value objects |
| `lookup/LookupServiceRegistry.java` | auto-built registry |
| `lookup/impl/StubLookupService.java` | shippable stub `@Component` |
| `exception/UnknownLookupServiceException.java` | thrown by registry; mapped in [`IvrExceptionHandler`](src/main/java/com/yourco/ivr/api/IvrExceptionHandler.java) |
| `api/LookupServiceController.java` + `api/dto/LookupServiceDescriptor.java` | discovery API |
| `domain/config/VerificationBinding.java` | config value object |
| Tests: `StubLookupService` wiring + brand-config verification integration test | project rule #3 |

**Modified**
| File | Change |
|---|---|
| `domain/config/BrandAuthConfig.java` | add `verificationSources` map + null-safe accessor |
| `engine/AuthEngine.java` | inject registry; two-stage `validateExternally`; pass `config` in |
| `validator/ValidationErrorCode.java` | add `VERIFICATION_FAILED`, `VERIFICATION_UNAVAILABLE` |
| `service/BrandService.java` | inject registry; validate bindings (§7) |
| `api/IvrExceptionHandler.java` | map `UnknownLookupServiceException` → 400 |
| `src/main/ui/src/pages/BrandEditor.tsx` (+ types) | Verification Sources UI |
| `README.md`, `IVR_Auth_Engine_Technical_Spec.md`, `ONBOARDING.md` | document the feature (rule #4) |

---

## 11. Out of scope (future increments)

- **Real HTTP lookup services** (Experian, core banking, etc.) — added later as new `@Component`s; the framework here is what makes that a drop-in.
- **Secret resolution** from env/secret manager for `params` aliases.
- **Per-path / per-level** binding granularity (this doc is brand-level per token). The model could later move/augment `verificationSources` onto `TokenPath` without breaking the brand-level map.
- **Per-session verification result caching** and configurable per-service timeouts/retry/circuit-breaking.
- **`paramSchema`-driven validation** of param inputs on save.

---

## 12. Implementation plan (phased)

1. **SPI + registry + stub + exception** — `lookup/*`, `StubLookupService`, `UnknownLookupServiceException`. Unit test the registry (lookup by id, duplicate-id failure, `forToken`).
2. **Config model** — `VerificationBinding`, `BrandAuthConfig.verificationSources` + accessor. Confirm existing brand JSON still deserializes.
3. **Engine wiring** — two-stage `validateExternally`, new error codes. Integration test: brand bound to stub (force PASS and FAIL) drives a full session to `AUTHENTICATED` / `FAILED`.
4. **Save-time validation** — `BrandService.validate()` rules + test (unknown serviceId, unsupported token → 400).
5. **Discovery API** — `LookupServiceController` + DTO + test (`GET /api/lookup-services`).
6. **Frontend** — fetch services, Verification Sources UI, serialize binding, `npm run build`.
7. **Docs** — update README / Technical Spec / ONBOARDING per rule #4.

## 13. Verification (how to test end-to-end)

- `mvn test` — new unit + integration tests green.
- `mvn spring-boot:run`, then `GET /api/lookup-services` returns `stub-verify`.
- Create a brand binding `SSN_LAST4 → stub-verify`; run a session via `POST /ivr/authenticate` (start → submit SSN) and confirm the processing log shows the verification step and reaches `AUTHENTICATED`. Flip the stub to fail (via `params`) and confirm `FAIL` + path fallback behavior.
- In the UI: open the brand, pick a service from the dropdown for a token, save, reload, confirm it persists and appears in the JSON tab.
