# Design: Configurable Token Lookup / Verification Services

Status: **Implemented** · Scope: backend · Tests: `BackendVerificationIntegrationTest` (green)

> The original design proposed per-brand config in `verificationSources`. During construction, the approach was simplified to **in-code bindings** via a Spring bean. This doc describes the actual implementation.

---

## 1. Context & goal

Today a token is checked only for **format** — e.g. `SsnLast4Validator` confirms "4 digits", nothing more. There is no way to verify a token against a **real backend system of record** (e.g. "does this SSN match the customer record in Experian / Core Banking?").

**Goal:** Let a developer add a backend verification step by implementing one Spring `@Component` interface and wiring it to a token type in a single code file. No brand config or UI changes are required.

---

## 2. Core idea: separate *format validation* from *backend verification*

| Layer | Runs when | Cost |
|---|---|---|
| **Format validation** (`TokenValidator`) | Always | Cheap, no network |
| **Backend verification** (`TokenLookupService`) | Only if the token is bound in code to a service | Network call |

The binding *token → service* lives in **code** (`DefaultVerificationBindings`), not in brand config.

### The seam we hook into

`AuthEngine.validateExternally()` is the single choke point through which every token submission flows. It now runs a two-stage pipeline:

```java
private ValidationResult validateExternally(IvrSession session, TokenType tokenType, String tokenValue) {
    // Stage 1 — format gate (cheap, no network)
    ValidationResult fmt = validatorRegistry.resolve(session.getBrandId(), tokenType)
                                            .validate(buildCtx(...));
    if (!fmt.isValid()) return fmt;

    // Stage 2 — backend verification, only if this token is bound to a service
    VerificationBinding binding = verificationBindings.bindingFor(session.getBrandId(), tokenType);
    if (binding == null) return ValidationResult.ok();
    return verifyAgainstBackend(session, tokenType, tokenValue, binding);
}
```

All retry / path-fallback / audit-logging logic downstream keys off the returned `ValidationResult` — nothing changes downstream.

---

## 3. Backend component design

### 3.1 The Lookup Service SPI

Package `com.yourco.ivr.lookup`:

```java
public interface TokenLookupService {
    String id();                         // stable registry key, e.g. "experian-ssn"
    String displayName();                // UI label
    String description();                // UI helptext
    Set<TokenType> supportedTokens();    // which token types this service can verify
    LookupResult verify(LookupRequest req);
}
```

```java
public final class LookupRequest {
    private final TokenType tokenType;
    private final String tokenValue;         // NEVER logged
    private final String callerId;
    private final String brandId;
    private final Map<TokenType, String> sessionTokens;  // already-collected tokens
    private final Map<String, String> params;            // per-binding params
}

public final class LookupResult {
    private final boolean verified;
    private final ValidationErrorCode code;  // null when verified
    private final String detail;             // safe, non-sensitive
    public static LookupResult ok();
    public static LookupResult fail(ValidationErrorCode code, String detail);
}
```

### 3.2 The registry

```java
@Component
public class LookupServiceRegistry {
    public LookupServiceRegistry(List<TokenLookupService> services) {
        // index by id(); fail fast on duplicate ids
    }
    public TokenLookupService get(String id);                 // throws UnknownLookupServiceException
    public Collection<TokenLookupService> all();
    public List<TokenLookupService> forToken(TokenType t);
}
```

### 3.3 The stub service

```java
@Component
public class StubLookupService implements TokenLookupService {
    public String id() { return "stub-verify"; }
    public Set<TokenType> supportedTokens() { return EnumSet.allOf(TokenType.class); }
    public LookupResult verify(LookupRequest req) { return LookupResult.ok(); }
}
```

---

## 4. Wiring mechanism (in-code, not per-brand config)

### 4.1 VerificationBindings interface

```java
public interface VerificationBindings {
    VerificationBinding bindingFor(String brandId, TokenType tokenType);
}
```

### 4.2 DefaultVerificationBindings (the one file to edit)

```java
@Component
public class DefaultVerificationBindings implements VerificationBindings {

    private final Map<TokenType, VerificationBinding> bindings = bindings();

    private static Map<TokenType, VerificationBinding> bindings() {
        Map<TokenType, VerificationBinding> m = new EnumMap<>(TokenType.class);
        // ← Add entries here to enable backend verification:
        // m.put(TokenType.SSN_LAST4, new VerificationBinding("stub-verify", null, true));
        return m;
    }

    @Override
    public VerificationBinding bindingFor(String brandId, TokenType tokenType) {
        return bindings.get(tokenType);
    }
}
```

### 4.3 VerificationBinding value object

```java
public class VerificationBinding {
    private String serviceId;             // must exist in LookupServiceRegistry
    private Map<String, String> params;   // optional per-binding params
    private boolean failClosed = true;    // backend unavailable ⇒ FAIL (true) or skip (false)
}
```

### 4.4 Backward compatibility

The map is **empty by default** — every token is format-checked only until a binding is added. No existing behavior changes. The former `BrandAuthConfig.verificationSources` approach was removed during implementation; backend verification is now entirely code-driven.

---

## 5. Engine wiring

`AuthEngine` gains two constructor dependencies: `LookupServiceRegistry` and `VerificationBindings`. The two-stage pipeline runs as described in §2.

On any `RuntimeException` (timeout, unavailable service, unknown ID):

- `failClosed = true` (default) → treat as validation failure
- `failClosed = false` → pass through on format check alone

---

## 6. Discovery API

A `LookupServiceController` exposes the registry for administrative use:

```
GET /api/lookup-services
→ 200 [
    { "id": "stub-verify",
      "displayName": "Stub Verifier (always pass)",
      "description": "...",
      "supportedTokens": ["ACCOUNT_NUMBER","PIN","OTP","SSN_LAST4","CARD_LAST4","DATE_OF_BIRTH","VOICE_PRINT"]
    }
]
```

Read-only; no authentication required.

---

## 7. Adding a real backend integration

1. Implement `TokenLookupService` as a `@Component`
2. Add an entry in `DefaultVerificationBindings.bindings()`:
   ```java
   m.put(TokenType.SSN_LAST4, new VerificationBinding("my-service", params, true));
   ```
3. No brand config changes, no UI changes, no restart of anything beyond the normal Spring restart.

---

## 8. Cross-cutting concerns

- **No raw token logging**: `LookupRequest.tokenValue` is never logged. Audit lines carry `serviceId`, `tokenType`, and outcome only.
- **Secrets**: API keys must not live in binding params. Use environment variables or a secret manager; `params` may carry aliases.
- **Latency/resilience**: Each real service should enforce its own timeout; the engine treats thrown exceptions via the `failClosed` policy.
- **Fail policy**: Default `failClosed = true`. A binding may opt into graceful degradation per token.

---

## 9. File-by-file change list

**New files:**

| File | Purpose |
|---|---|
| `lookup/TokenLookupService.java` | SPI |
| `lookup/LookupRequest.java`, `lookup/LookupResult.java` | Request/result value objects |
| `lookup/LookupServiceRegistry.java` | Auto-built registry |
| `lookup/VerificationBindings.java` | Interface for binding resolution |
| `lookup/DefaultVerificationBindings.java` | Default in-code bindings (empty) |
| `lookup/VerificationBinding.java` | Binding config value object |
| `lookup/impl/StubLookupService.java` | Dev stub `@Component` |
| `exception/UnknownLookupServiceException.java` | Thrown by registry |
| `api/LookupServiceController.java` + `api/dto/LookupServiceDescriptor.java` | Discovery API |
| `api/dto/ProcessingEvent.java` | Audit log entries in responses |

**Modified files:**

| File | Change |
|---|---|
| `engine/AuthEngine.java` | Two-stage `validateExternally`, 7 deps, `ProcessingEvent` audit log, wrong-type guard |
| `validator/ValidationErrorCode.java` | Added `VERIFICATION_FAILED`, `VERIFICATION_UNAVAILABLE` |
| `api/IvrExceptionHandler.java` | Map `UnknownLookupServiceException` → 400 |
| `service/AuthenticateService.java` | 7 deps, disambiguation wiring, initial tokens support |

---

## 10. Verification (how to test end-to-end)

1. `mvn test` — `BackendVerificationIntegrationTest` green
2. `mvn spring-boot:run`
3. `GET /api/lookup-services` returns `stub-verify`
4. Bind `SSN_LAST4 → stub-verify` in `DefaultVerificationBindings`, restart, run a session through `POST /ivr/authenticate` (start → submit SSN) and confirm the processing log shows the verification step and reaches `AUTHENTICATED`
