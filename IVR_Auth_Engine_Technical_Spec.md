# IVR Token Authentication Engine
### Multi-Brand | Progressive Auth Levels | Rule-Driven
**Technical Implementation Document — Version 2.1 | Java 8 | Spring Boot 2.7.x**

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Domain Model](#2-domain-model)
3. [Brand Configuration (JSON)](#3-brand-configuration-json)
4. [Auth Engine — Core Logic](#4-auth-engine--core-logic)
5. [Token Validator Layer](#5-token-validator-layer)
6. [Call Transfer](#6-call-transfer)
7. [REST API Specification](#7-rest-api-specification)
8. [Session Service](#8-session-service)
9. [Session Storage (SQLite)](#9-session-storage-sqlite)
10. [Key Sequence Flows](#10-key-sequence-flows)
11. [Exception Handling](#11-exception-handling)
12. [Package Structure](#12-package-structure)
13. [Party Disambiguation](#13-party-disambiguation)
14. [Customer Preferences](#14-customer-preferences)

---

## 1. System Overview

This document specifies the design and implementation of a multi-brand IVR Token Authentication Engine built on Java 8 and Spring Boot 2.7.x. The system allows multiple brands to define independent authentication rule sets, share token validators, and handle call transfer. Callers can target a specific auth level, upgrade mid-session, and reuse previously validated tokens within the same session.

### 1.1 Core Capabilities

- **Multi-brand rule isolation** — each brand carries its own level definitions, token paths, retry limits, and lockout policies
- **Shared token validators** — a global registry of validators (OTP, PIN, account number, voice, etc.) any brand can reference
- **Backend verification** — a staged pipeline (format gate → optional party-field check for identification-only brands → optional backend lookup) verifies tokens against systems of record via pluggable `TokenLookupService`
- **Progressive authentication** — a session starts at `NONE`, reaches targeted levels step by step, and can escalate further without restarting
- **Identification-only mode** — brands that only need to identify the caller (no auth tokens) can set `identificationOnly: true`
- **Declarative rules** — all brand behaviour is driven by JSON config; no logic changes require code deployments
- **Call transfer support** — external IVR systems can transfer callers mid-authentication with pre-validated tokens; per-source policies control which tokens and auth levels are honored

### 1.2 High-Level Architecture

| Layer | Responsibility |
|---|---|
| REST API | Accepts IVR platform calls; classifies each payload into a `RequestAction` and maps HTTP to session commands |
| Auth Engine | Core state machine; drives path progression and the token submission lifecycle. Delegates path navigation, slot resolution, preference filtering, validation, and response building to focused sub-components |
| Engine sub-components | `ActivePathManager` (path resolution/switching), `TokenSlotResolver` (backup→slot mapping + accepted tokens), `PreferenceFilter` (blocked-token skipping), `ExternalValidator` (two/three-stage validation), `ResponseAssembler` (response construction), `PromptResolver` (caller-facing prompt text) |
| Rules Registry | Loads and serves brand-specific `BrandAuthConfig` objects from external `./config/brands/` directory |
| Validator Registry | Maps token types to their `TokenValidator` implementations |
| Lookup Service Registry | Maps service IDs to `TokenLookupService` implementations for backend verification |
| Transfer Policies Registry | Loads and serves per-source `TransferPolicy` objects from external `./config/transfers/` |
| Session Store | SQLite-backed via JdbcTemplate; holds `IvrSession` with full token/level/party state; optimistic locking via version column |
| Party Lookup | Pluggable interface for ANI → Party resolution |
| Disambiguation Engine | Fixed rule chain + token matching for multi-party resolution |
| Customer Preference Provider | Loads per-customer blocked tokens and level caps |

---

## 2. Domain Model

### 2.1 Core Enums

```java
// TokenType.java — each constant carries a human-readable display name used in IVR prompts
public enum TokenType {
    ACCOUNT_NUMBER("account number"),
    PIN("PIN"),
    OTP("one-time passcode"),
    SSN_LAST4("last 4 digits of your SSN"),
    VOICE_PRINT("voice verification"),
    DATE_OF_BIRTH("date of birth"),
    CARD_LAST4("last 4 digits of your card");

    private final String displayName;
    TokenType(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}

// AuthLevel.java  — rank drives upgrade comparisons
public enum AuthLevel {
    NONE(0), BASIC(1), STANDARD(2), ELEVATED(3), ADMIN(4);

    private final int rank;
    AuthLevel(int rank) { this.rank = rank; }
    public int getRank() { return rank; }
    public boolean isHigherThan(AuthLevel other) { return this.rank > other.rank; }
}
```

### 2.2 Brand Configuration Model

```java
// BrandAuthConfig.java
@Data
public class BrandAuthConfig {
    private String brandId;                           // e.g. "BRAND_A"
    private Map<AuthLevel, LevelRule> levelRules;     // one rule per level (not required when identificationOnly)
    private boolean identificationOnly;               // when true: identify-and-stop, access level stays NONE
    private LevelDeterminationConfig levelDetermination; // optional: derive target level from call conditions
}
// Disambiguation is always-on and not configurable — see DisambiguationEngine (fixed 3-round
// limit + EXCLUDE_INACTIVE / PREFER_PRIMARY_ANI rule chain). No per-brand config.

// LevelDeterminationConfig.java — optional declarative level selection
@Data
public class LevelDeterminationConfig {
    private List<LevelSelectionRule> rules;   // evaluated in order; first match wins
    private AuthLevel defaultLevel;           // used when no rule matches
}

// LevelSelectionRule.java
@Data
public class LevelSelectionRule {
    private String description;               // human label, surfaced in the processing log
    private AuthLevel level;                  // level selected when all conditions pass
    private List<LevelCondition> conditions;  // all must pass; empty = always match
}

// LevelCondition.java + LevelConditionType.java
@Data
public class LevelCondition {
    private LevelConditionType type;          // ANI_MATCHED | PARTY_ACTIVE | PRIMARY_ANI | PARTY_ATTRIBUTE
    private String key;                       // required when type = PARTY_ATTRIBUTE (attribute map key)
    private String value;                     // expected attribute value (PARTY_ATTRIBUTE)
}

// LevelRule.java
@Data
public class LevelRule {
    private List<TokenPath> paths;              // [0]=primary, [1..n]=fallbacks
    private int maxRetriesPerToken;             // default retries per required-token slot
    private Map<TokenType, Integer> tokenRetryLimits;  // per-token retry overrides
    private int lockoutSeconds;                 // 0 = no lockout

    public int getMaxRetriesFor(TokenType tokenType) {
        if (tokenRetryLimits != null && tokenRetryLimits.containsKey(tokenType)) {
            return tokenRetryLimits.get(tokenType);
        }
        return maxRetriesPerToken;
    }
}

// TokenPath.java
@Data
public class TokenPath {
    private int pathIndex;
    private List<TokenType> requiredTokens;  // ordered: prompt in this order
    private String description;              // e.g. "PIN path"

    // Optional: maps a required token to alternative tokens that can satisfy it.
    // Example: PIN -> [SSN_LAST4, DATE_OF_BIRTH] means providing SSN_LAST4
    // or DATE_OF_BIRTH counts as meeting the PIN requirement.
    private Map<TokenType, List<TokenType>> backupTokens;
}
```

**Identification-only brands.** When `identificationOnly = true`, the brand's goal is identification rather than authentication. Party lookup and disambiguation run unchanged. Two sub-modes are supported:

- **No `NONE` rule (pure identify-and-stop).** The moment a single party is resolved, the engine finalizes the session via `AuthEngine.onPartyResolved()` → `finalizeIdentification()` without collecting any tokens: it sets `currentLevel = NONE`, `targetLevel = NONE`, `status = AUTHENTICATED`, and returns the `matchedPartyId`.
- **With a `NONE`-level rule (identification-token collection).** The brand may define a `levelRules` entry keyed `NONE` whose paths list identification tokens (e.g. `ACCOUNT_NUMBER` + `PIN`). On party resolution the engine runs normal progress evaluation against the `NONE` rule, prompting for each token. These tokens are verified against the matched `Party`'s fields by `ExternalValidator` (see §4.4). Authentication completes (`status = AUTHENTICATED`, `currentLevel` stays `NONE`) once the `NONE` path is satisfied. Any `initialTokens` are consumed at start only when a `NONE` rule is present.

For identification-only brands the `start` request always forces `targetLevel = NONE`, `levelRules` are not strictly required (`BrandService.validate()` relaxes the check), and `escalate()` is rejected with an `IllegalArgumentException` (HTTP 400).

### 2.3 Session State

```java
// IvrSession.java
@Data
public class IvrSession {
    private String sessionId;
    private String brandId;
    private String callerId;
    private AuthLevel currentLevel;            // highest confirmed level
    private AuthLevel targetLevel;             // what caller is trying to reach
    private SessionStatus status;
    private SessionPhase phase;                // DISAMBIGUATION or AUTHENTICATING

    // Tokens collected this session (raw values — encrypt at rest)
    private Map<TokenType, String> collectedTokens;

    // Tokens fully validated — key to auth decisions
    private Set<TokenType> validatedTokens;

    // Tracks per-token attempt counts within the active path
    private Map<TokenType, Integer> attemptCounts;

    // Active path index per level (allows independent path tracking)
    private Map<AuthLevel, Integer> activePathIndexByLevel;

    // Party disambiguation fields
    private List<Party> candidateParties;
    private Party matchedParty;
    private CustomerPreference customerPreferences;
    private int disambiguationAttemptCount;

    // Source system that transferred this call (null if session started locally)
    private String transferredFrom;

    // Optimistic locking for concurrent update safety
    private int version;

    private Instant lockedUntil;
    private Instant createdAt;
    private Instant lastActivityAt;
}

public enum SessionStatus {
    COLLECTING, VALIDATING, AUTHENTICATED, REDIRECT_TO_AGENT, EXPIRED, FAILED
}

public enum SessionPhase {
    DISAMBIGUATION, AUTHENTICATING
}
```

---

## 3. Brand Configuration (JSON)

Each brand's rule set lives in its own JSON file in the external directory (`./config/brands/`), loaded at startup and managed via the UI or API. Brand IDs must match exactly what the IVR platform sends in session start requests.

### 3.1 Brand A — Full Example

```json
{
  "brandId": "BRAND_A",
  "levelRules": {
    "BASIC": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Account lookup",
          "requiredTokens": ["ACCOUNT_NUMBER"]
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 0
    },
    "STANDARD": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Account + PIN",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"]
        },
        {
          "pathIndex": 1,
          "description": "Account + OTP fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"]
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    },
    "ELEVATED": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Full factor",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN", "OTP"]
        },
        {
          "pathIndex": 1,
          "description": "Voice biometric fallback",
          "requiredTokens": ["ACCOUNT_NUMBER", "VOICE_PRINT", "OTP"]
        }
      ],
      "maxRetriesPerToken": 2,
      "lockoutSeconds": 600
    }
  },
  "levelDetermination": {
    "rules": [
      { "description": "Premium segment customers", "level": "ELEVATED",
        "conditions": [ { "type": "PARTY_ATTRIBUTE", "key": "segment", "value": "PREMIUM" } ] },
      { "description": "Active account holders", "level": "STANDARD",
        "conditions": [ { "type": "PARTY_ACTIVE" } ] }
    ],
    "defaultLevel": "STANDARD"
  }
}
```

> BRAND_A's shipped config includes this `levelDetermination` block, so clients (including the admin console) can start BRAND_A calls without a caller-supplied `targetLevel` — the level is derived (see §3.4). Brands without the block keep the legacy requirement: `targetLevel` on start, used verbatim (the shipped sample brands all define `levelDetermination`; the legacy path is covered by tests via an in-memory brand).

### 3.2 Brand B — Simpler Config

```json
{
  "brandId": "BRAND_B",
  "levelRules": {
    "BASIC": {
      "paths": [
        {
          "pathIndex": 0,
          "requiredTokens": ["ACCOUNT_NUMBER"]
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 0
    },
    "STANDARD": {
      "paths": [
        {
          "pathIndex": 0,
          "requiredTokens": ["ACCOUNT_NUMBER", "DATE_OF_BIRTH"]
        },
        {
          "pathIndex": 1,
          "requiredTokens": ["ACCOUNT_NUMBER", "CARD_LAST4"]
        }
      ],
      "maxRetriesPerToken": 2,
      "lockoutSeconds": 300
    }
  }
}
```

### 3.3 Identification-Only Brand with a `NONE` Rule

An identification-only brand may carry a single `NONE`-level rule whose tokens are verified against the matched party's record (see §2.2 and §4.4). Authentication completes at `NONE` level once the path is satisfied.

```json
{
  "brandId": "ID_ONLY_BRAND",
  "identificationOnly": true,
  "levelRules": {
    "NONE": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Account + PIN identification",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"],
          "backupTokens": { "PIN": ["SSN_LAST4"] }
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 0
    }
  }
}
```

A pure identify-and-stop brand simply omits `levelRules` entirely (set `identificationOnly: true` with no rules); the caller is identified on party resolution with no token collection.

### 3.4 Declarative Level Determination

Real IVR flows don't let the caller pick their auth level — the system leads the customer to the level the requested action requires. Brands can encode that decision declaratively via `levelDetermination`; the engine evaluates it once the caller's party is resolved (after ANI lookup / disambiguation) and **overrides any caller-supplied `targetLevel`**. The chosen level and matched rule are reported in the start response's `processingLog` (e.g. `Level determined: ELEVATED — rule "Premium segment customers"`).

```json
{
  "brandId": "DEMO_BRAND",
  "levelRules": { "BASIC": { "paths": [...] }, "STANDARD": { "paths": [...] }, "ELEVATED": { "paths": [...] } },
  "levelDetermination": {
    "rules": [
      {
        "description": "Premium segment customers",
        "level": "ELEVATED",
        "conditions": [ { "type": "PARTY_ATTRIBUTE", "key": "segment", "value": "PREMIUM" } ]
      },
      {
        "description": "Active account holders",
        "level": "STANDARD",
        "conditions": [ { "type": "PARTY_ACTIVE" } ]
      }
    ],
    "defaultLevel": "BASIC"
  }
}
```

**Condition types** (evaluated against the resolved `Party`):

| Type | Passes when |
|---|---|
| `ANI_MATCHED` | The caller's ANI resolved to a party (always true at evaluation time — an empty lookup is rejected earlier). |
| `PARTY_ACTIVE` | `party.isActive()`. |
| `PRIMARY_ANI` | `party.isPrimaryAni()`. |
| `PARTY_ATTRIBUTE` | `party.additionalAttributes[key]` equals `value` (e.g. `segment=PREMIUM`). |

**Semantics.**

- Rules are evaluated top to bottom; the **first** rule whose conditions **all** pass selects the level. A rule with an empty `conditions` list always matches (catch-all).
- If no rule matches, `defaultLevel` is used.
- The derived level is clamped to the customer's `maxAllowedLevel` preference (same guard as `escalate()`).
- Identification-only brands always stay at `NONE` — `levelDetermination` is skipped.
- Brands **without** `levelDetermination` keep the legacy behavior: the caller-supplied `targetLevel` is used verbatim and is **required** on start. For brands **with** it, `targetLevel` is optional and ignored.
- `BrandService.validate()` rejects: a section with no rules and no `defaultLevel`, a rule/default level not present in `levelRules`, a condition without a `type`, and `PARTY_ATTRIBUTE` conditions without a `key`.

Evaluation lives in `LevelDeterminationEngine.determine(config, party)` → `DeterminationResult(level, reason)`; the reason string only contains brand-config constants (never party-supplied values), so no sensitive data enters the log. The result is applied in `AuthenticateService.proceedAfterPartyResolved()` via `applyLevelDetermination()` before any progress evaluation. A ready-to-run example ships as `config/brands/determination_demo.json` (`DEMO_BRAND`).

---

## 4. Auth Engine — Core Logic

The `AuthEngine` is the heart of the system. It is stateless itself; all state is passed via `IvrSession`. This enables horizontal scaling with no affinity requirements.

### 4.1 AuthEngine.java

As of v2.1 the engine has been decomposed: `AuthEngine` orchestrates the token lifecycle and delegates the mechanics to focused collaborators. It is still stateless.

Key design points in the actual implementation:

- **8 constructor dependencies**: `BrandRulesRegistry`, `SessionRepository`, `PromptResolver`, `DisambiguationEngine`, `ActivePathManager`, `TokenSlotResolver`, `ExternalValidator`, `AttemptCoordinator`
- **Delegated collaborators**:
  - `ActivePathManager` — resolves the active `ActivePath` (rule + index + path), switches to fallback paths, advances/fails on exhaustion, and handles redirect-to-agent
  - `TokenSlotResolver` — maps a submitted backup token to its required slot (`resolveBackupToken` / `findRequiredTokenForSlot`), computes `findNextRequired`, and builds the `acceptedTokens` list
  - `PreferenceFilter` — static `isBlocked()` / `findAlternativeToken()` helpers for blocked-token skipping
  - `ExternalValidator` — runs the format → (party-field) → backend validation pipeline (see §4.4)
  - `ResponseAssembler` — static factory methods (`collecting`, `authenticated`, `failed`, `redirectToAgent`) that build `AuthenticateResponse` objects from a session
- **Session status `REDIRECT_TO_AGENT`** replaces the former `LOCKED` — the session redirects to a live agent for the configured `lockoutSeconds` duration; once `lockedUntil` elapses the next submission auto-resets the session to `COLLECTING`
- **Terminal short-circuit**: once a session is `AUTHENTICATED` or `FAILED`, further token submissions return the current snapshot via `AuthenticateResponse.fromSession()` without mutation
- **Processing log**: every token submission returns a `processingLog` (list of `ProcessingEvent`) narrating the engine's decisions
- **Wrong-type guard**: submitting a token type not in the accepted list decrements the required slot's retry budget but does NOT trigger a path switch — this prevents probing for valid token types. Exhausting retries on a wrong type redirects straight to an agent
- **Backup token resolution**: submitted backup tokens (e.g. SSN_LAST4 for PIN) are mapped to the required slot so session state always records the canonical type
- **Failure tracking at required-slot level**: retry counts accumulate against the required-token slot (PIN), not the submitted backup type. Cycling across backups (PIN → SSN_LAST4 → DATE_OF_BIRTH) does not bypass the retry limit
- **Customer preference filtering**: `PreferenceFilter.isBlocked()` and `findAlternativeToken()` automatically skip blocked tokens and try backup alternatives before advancing to the next path
- **`acceptedTokens` in responses**: each response carries the list of token types the client may submit at the current step (required token + unblocked backups)

`TokenSlotResolver` is wired in `EngineConfig` because it needs a method-reference to `ActivePathManager.resolveActivePath` (a `BiFunction<IvrSession, BrandAuthConfig, ActivePath>`).

```java
@Service
public class AuthEngine {

    private final BrandRulesRegistry rulesRegistry;
    private final SessionRepository sessionRepo;
    private final PromptResolver promptResolver;
    private final DisambiguationEngine disambiguationEngine;
    private final ActivePathManager pathManager;
    private final TokenSlotResolver slotResolver;
    private final ExternalValidator externalValidator;

    public AuthEngine(BrandRulesRegistry rulesRegistry,
                      SessionRepository sessionRepo,
                      PromptResolver promptResolver,
                      DisambiguationEngine disambiguationEngine,
                      ActivePathManager pathManager,
                      TokenSlotResolver slotResolver,
                      ExternalValidator externalValidator) {
        this.rulesRegistry = rulesRegistry;
        this.sessionRepo = sessionRepo;
        this.promptResolver = promptResolver;
        this.disambiguationEngine = disambiguationEngine;
        this.pathManager = pathManager;
        this.slotResolver = slotResolver;
        this.externalValidator = externalValidator;
    }

    public AuthenticateResponse submitTokenWithCaller(String sessionId, TokenType tokenType,
                                                      String tokenValue, String callerId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        resetExpiredRedirectOrThrow(session);   // REDIRECT_TO_AGENT guard + auto-reset
        verifyCallerOwnership(session, callerId);

        if (isTerminal(session)) {               // AUTHENTICATED / FAILED → return snapshot
            return AuthenticateResponse.fromSession(session);
        }

        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        session.getCollectedTokens().put(tokenType, tokenValue);  // value never logged/persisted

        if (session.getPhase() == SessionPhase.DISAMBIGUATION) {
            return handleDisambiguationPhase(session, config, tokenType, tokenValue);
        }
        return processTokenInAuthPhase(session, config, tokenType, tokenValue);
    }

    // processTokenInAuthPhase(): build processing log → wrong-type guard →
    //   externalValidator.validate() → on PASS: slotResolver.resolveBackupToken(),
    //   add to validatedTokens, clear attempt counts → evaluateProgress().
    //   On FAIL: handleValidationFailure() (re-prompt, then fallback path, then agent).

    public AuthenticateResponse onPartyResolved(IvrSession session, BrandAuthConfig config) {
        if (config.isIdentificationOnly() && !hasNoneRule(config)) {
            return finalizeIdentification(session);          // pure identify-and-stop
        }
        if (config.isIdentificationOnly()) {
            markCollectedTokensSatisfyingNoneRule(session, config);
        }
        return evaluateProgress(session, config);            // NONE-rule or standard auth
    }
}
```

### 4.2 evaluateProgress — with preference filtering

```java
public AuthenticateResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
    ActivePath active = pathManager.resolveActivePath(session, config);
    if (active.rule() == null) {
        throw new IllegalArgumentException("No rule defined for level: " + session.getTargetLevel());
    }
    if (active.path() == null) {  // active index points past the last fallback path
        return failSession(session, "Authentication failed. All retry attempts exhausted.");
    }

    LevelRule rule = active.rule();
    TokenPath activePath = active.path();

    // Next missing token on this path (null = path complete)
    TokenType nextToken = TokenSlotResolver.findNextRequired(session.getValidatedTokens(), activePath);
    if (nextToken == null) {
        return completeAuthentication(session, config);   // AUTHENTICATED (or finalizeIdentification)
    }

    TokenType originalRequired = nextToken;

    // Apply customer preference filtering
    if (PreferenceFilter.isBlocked(session, nextToken)) {
        nextToken = PreferenceFilter.findAlternativeToken(session, activePath, nextToken);
        if (nextToken == null) {
            return pathManager.advanceToNextPathOrFail(session, config, rule, active.index(),
                this::evaluateProgress);
        }
    }

    session.setStatus(SessionStatus.COLLECTING);
    sessionRepo.save(session);

    List<TokenType> acceptedTokens = slotResolver.buildAcceptedTokens(session, activePath,
        nextToken, originalRequired, t -> PreferenceFilter.isBlocked(session, t));
    String prompt = promptResolver.resolvePrompt(nextToken, activePath, rule.getMaxRetriesFor(nextToken));

    return collecting(session, nextToken, acceptedTokens, rule.getMaxRetriesFor(nextToken), prompt);
}
```

`completeAuthentication()` sets `currentLevel = targetLevel` and `status = AUTHENTICATED` for standard brands; for an identification-only brand whose `targetLevel` is `NONE` it routes to `finalizeIdentification()` instead.

### 4.3 Failure handling — with required-slot tracking

Failure handling is split by cause. Both paths first call `recordAttemptOrExhaust()`, which charges the failed attempt against the **required slot** (via `TokenSlotResolver.findRequiredTokenForSlot`) and re-prompts while retries remain; it returns `null` once the limit is hit. The two callers then differ in terminal behavior:

- **`handleValidationFailure`** (token format/backend rejected) — on exhaustion, tries `pathManager.switchToFallbackPath()`; if there is no next path, calls `pathManager.handleRedirectToAgent()`.
- **`handleWrongTypeFailure`** (token type not accepted at this step) — on exhaustion, goes **straight** to `handleRedirectToAgent()`; a wrong type never switches paths.

```java
private AuthenticateResponse recordAttemptOrExhaust(IvrSession session, BrandAuthConfig config,
                                                    TokenType tokenType, List<ProcessingEvent> procLog) {
    LevelRule rule = ActivePathManager.levelRule(config, session);
    // Track against the required-token slot, not the submitted backup type
    TokenType requiredToken = slotResolver.findRequiredTokenForSlot(session, config, tokenType);
    int remaining = recordFailedAttempt(session, rule, requiredToken, procLog);
    if (remaining > 0) {
        return repromptForSlot(session, rule, requiredToken, remaining, procLog);  // still COLLECTING
    }
    return null;  // retries exhausted — caller decides terminal behavior
}

private AuthenticateResponse handleValidationFailure(IvrSession session, BrandAuthConfig config,
                                                     TokenType tokenType, List<ProcessingEvent> procLog) {
    AuthenticateResponse reprompt = recordAttemptOrExhaust(session, config, tokenType, procLog);
    if (reprompt != null) return reprompt;

    LevelRule rule = ActivePathManager.levelRule(config, session);
    AuthenticateResponse switched = pathManager.switchToFallbackPath(session, config, rule, procLog,
        this::evaluateProgress);
    if (switched != null) return switched;
    return pathManager.handleRedirectToAgent(session, rule, procLog);  // no path left → agent
}
```

`ActivePathManager.handleRedirectToAgent()` sets `status = REDIRECT_TO_AGENT` and `lockedUntil = now + rule.lockoutSeconds`. `switchToFallbackPath()` advances the active path index, clears attempt counts, retains any tokens already validated that the new path also needs, and re-evaluates.

### 4.4 Validation pipeline — `ExternalValidator`

Validation is owned by the standalone `ExternalValidator` `@Component` (constructor deps: `TokenValidatorRegistry`, `LookupServiceRegistry`, `VerificationBindings`, `BrandRulesRegistry`). It runs up to three stages, short-circuiting on the first failure:

1. **Format gate** (always) — cheap, in-process check via `TokenValidatorRegistry.resolve(brandId, type)`.
2. **Party-field verification** (identification-only brands only) — compares the submitted value against the matched `Party`'s corresponding field using `PartyTokenFields.FIELD_ACCESSORS`. A mismatch fails with `VERIFICATION_FAILED`. If the brand is not identification-only, the party has no such field, or the field is null, this stage is skipped (passes).
3. **Backend gate** (only if a `VerificationBinding` exists for the token) — calls the bound `TokenLookupService`; on an exception, behavior depends on the binding's `failClosed` flag.

```java
@Component
public class ExternalValidator {

    public ValidationResult validate(IvrSession session, TokenType tokenType, String tokenValue) {
        // Stage 1 — format gate (cheap, no network)
        TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
        ValidationResult formatResult = validator.validate(new TokenValidationContext(
            tokenType, tokenValue, session.getCallerId(),
            session.getCollectedTokens(), session.getBrandId()));
        if (!formatResult.isValid()) return formatResult;

        // Stage 1b — party-field verification for identification-only brands
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        if (config.isIdentificationOnly()) {
            ValidationResult partyResult = verifyAgainstParty(session, tokenType, tokenValue);
            if (!partyResult.isValid()) return partyResult;
        }

        // Stage 2 — backend verification (only if bound to a service)
        VerificationBinding binding = verificationBindings.bindingFor(session.getBrandId(), tokenType);
        if (binding == null) return ValidationResult.ok();
        return verifyAgainstBackend(session, tokenType, tokenValue, binding);  // failClosed-aware
    }
}
```

`PartyTokenFields` (`com.yourco.ivr.engine`) is the single source of truth mapping a `TokenType` to its `Party` field accessor — `ACCOUNT_NUMBER`, `DATE_OF_BIRTH`, `SSN_LAST4`, `CARD_LAST4`. It is shared by both `ExternalValidator` (party-field stage) and `DisambiguationEngine` (token selection/matching).

### 4.5 escalate — with identification-only guard

```java
public AuthenticateResponse escalate(String sessionId, AuthLevel newTarget) {
    IvrSession session = sessionRepo.getOrThrow(sessionId);
    BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
    if (config.isIdentificationOnly()) {
        throw new IllegalArgumentException("Escalation is not supported for identification-only brands");
    }
    if (!newTarget.isHigherThan(session.getCurrentLevel())) {
        throw new IllegalArgumentException("Target must exceed current level");
    }
    session.setTargetLevel(newTarget);
    sessionRepo.save(session);
    return evaluateProgress(session, config);
}
```

### 4.6 Identification-only finalization

`finalizeIdentification()` is reached only on the **pure** identify-and-stop path — an identification-only brand with no `NONE` rule, via `onPartyResolved()` (§4.1). Brands that define a `NONE` rule instead flow through normal `evaluateProgress()` / `completeAuthentication()` (§4.2), which also calls `finalizeIdentification()` once the path completes.

```java
private AuthenticateResponse finalizeIdentification(IvrSession session) {
    session.setCurrentLevel(AuthLevel.NONE);
    session.setTargetLevel(AuthLevel.NONE);
    session.setStatus(SessionStatus.AUTHENTICATED);
    sessionRepo.save(session);
    return authenticated(session, "Caller identified.");   // ResponseAssembler factory
}
```

---

## 5. Token Validator Layer

Token validators are stateless Spring beans. All validators implement a common interface. The `TokenValidatorRegistry` resolves the correct validator for each token type. Brand-specific overrides can be registered to shadow the default.

### 5.1 TokenValidator Interface

```java
public interface TokenValidator {
    TokenType supportedType();
    ValidationResult validate(TokenValidationContext ctx);
}

@Value
public class TokenValidationContext {
    TokenType tokenType;
    String tokenValue;
    String callerId;
    Map<TokenType, String> sessionTokens;   // other collected tokens as context
    String brandId;
}

@Value
public class ValidationResult {
    boolean valid;
    ValidationErrorCode errorCode;  // null if valid

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }
    public static ValidationResult fail(ValidationErrorCode code) {
        return new ValidationResult(false, code);
    }
}

public enum ValidationErrorCode {
    INVALID,                  // fails format requirements (length, parse, blank)
    EXPIRED,                  // e.g. OTP past its validity window
    NOT_FOUND,                // value not found in backend system of record
    RATE_LIMITED,             // backend rejected — too many attempts
    EXTERNAL_ERROR,           // unexpected error calling the backend
    VERIFICATION_FAILED,      // backend/party-field check ran but value did not match
    VERIFICATION_UNAVAILABLE  // backend unreachable; behavior depends on failClosed
}
```

### 5.2 Sample Validator — OTP

The shipped validators are format-only stubs sharing the `AbstractTokenValidator` base (null-check + `ValidationResult.fail(INVALID)` plumbing). Backend verification is a separate stage handled by `ExternalValidator` + `LookupExecutor` (see §5.5):

```java
@Component
public class OtpTokenValidator extends AbstractTokenValidator {

    @Override
    public TokenType supportedType() { return TokenType.OTP; }

    @Override
    protected boolean matches(String tokenValue) {
        return tokenValue.length() == 6;
    }
}
```

### 5.3 TokenValidatorRegistry

```java
@Component
public class TokenValidatorRegistry {

    // Default validators keyed by token type
    private final Map<TokenType, TokenValidator> defaults;

    // Brand-specific overrides: brandId -> (tokenType -> validator)
    private final Map<String, Map<TokenType, TokenValidator>> brandOverrides;

    @Autowired
    public TokenValidatorRegistry(List<TokenValidator> validators,
                                  List<BrandTokenValidatorOverride> overrides) {
        this.defaults = validators.stream()
            .collect(toMap(TokenValidator::supportedType, v -> v));
        this.brandOverrides = overrides.stream()
            .collect(groupingBy(BrandTokenValidatorOverride::getBrandId,
                toMap(o -> o.getValidator().supportedType(),
                      BrandTokenValidatorOverride::getValidator)));
    }

    public TokenValidator resolve(String brandId, TokenType type) {
        return Optional.ofNullable(brandOverrides.get(brandId))
            .map(m -> m.get(type))
            .orElseGet(() -> Optional.ofNullable(defaults.get(type))
                .orElseThrow(() -> new UnsupportedTokenTypeException(type)));
    }
}
```

### 5.4 Backend Verification Layer (Lookup Services)

`TokenValidator` only checks a token's **format**. A second, optional layer verifies the value against a real backend system of record. The pipeline runs: format gate → (optional) backend verification. Both must pass.

```java
public interface TokenLookupService {
    String id();                         // stable registry key, e.g. "experian-ssn"
    String displayName();
    String description();
    Set<TokenType> supportedTokens();
    LookupResult verify(LookupRequest request);
}
```

Lookup services are Spring `@Component`s auto-discovered into `LookupServiceRegistry`. **Adding a backend integration requires no per-brand code or brand config changes** — implement the interface and wire it to a token type in `DefaultVerificationBindings`:

```java
@Component
public class DefaultVerificationBindings implements VerificationBindings {
    private final Map<TokenType, VerificationBinding> bindings = bindings();

    private static Map<TokenType, VerificationBinding> bindings() {
        Map<TokenType, VerificationBinding> m = new EnumMap<>(TokenType.class);
        // Add an entry here to enable backend verification for a token type:
        // m.put(TokenType.SSN_LAST4, new VerificationBinding("stub-verify", null, true));
        return m;
    }

    @Override
    public VerificationBinding bindingFor(String brandId, TokenType tokenType) {
        return bindings.get(tokenType);
    }
}
```

Key differences from the earlier design (which proposed per-brand `verificationSources` config):
- **Bindings are in code** (`DefaultVerificationBindings`), not in brand JSON or the UI.
- The binding is **brand-agnostic** by default (the interface passes `brandId` for future use).
- An empty map means every token is format-checked only — no migration required.
- `GET /api/lookup-services` exposes the registry for reference.
- A configurable `stub-verify` service ships for development. See [`LOOKUP_SERVICE_DESIGN.md`](LOOKUP_SERVICE_DESIGN.md) for full design.

---

## 6. Call Transfer

Call transfer allows an external IVR/authentication system to hand off a caller mid-authentication to this engine. The caller's pre-validated tokens and achieved auth level are carried over, so the caller does not re-authenticate tokens they have already provided.

### 6.1 Transfer Policies

Each external source system is governed by a `TransferPolicy` that controls which tokens are honored and the maximum auth level accepted.

**Config file:** `config/transfers/transfer-policies.json`

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

### 6.2 Transfer Policy Model

```java
// TransferPolicy.java
@Data
public class TransferPolicy {
    private String sourceSystemId;
    private List<TokenType> honoredTokens;   // token types trusted from this source
    private AuthLevel maxHonoredLevel;       // highest level honored from this source
    private boolean enabled;                 // toggle on/off
}

// TransferPoliciesConfig.java — wrapper for JSON deserialization
@Data
public class TransferPoliciesConfig {
    private List<TransferPolicy> policies;
}
```

### 6.3 TransferPoliciesRegistry

```java
@Component
public class TransferPoliciesRegistry {

    private final Map<String, TransferPolicy> policies = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Loads JSON from ivr.transfer.config-dir (default ./config/transfers/)
        loadPolicies();
    }

    public TransferPolicy get(String sourceSystemId);
    public boolean isTokenHonored(String sourceSystemId, TokenType tokenType);
    public AuthLevel getMaxHonoredLevel(String sourceSystemId);
}
```

### 6.4 Transfer Request DTO

```java
// CallTransferRequest.java
@Data
public class CallTransferRequest {
    @NotBlank  private String sourceSystemId;     // e.g. "LEGACY_IVR"
    @NotBlank  private String brandId;             // target brand
    @NotBlank  private String callerId;            // caller identifier
    private AuthLevel currentLevel;                 // level reached in source (default NONE)
    @NotNull   private AuthLevel targetLevel;       // level to reach
    private List<TokenType> validatedTokens;        // token types already validated externally
}
```

### 6.5 Transfer Flow

Transfers are discriminated within the unified `POST /ivr/authenticate` endpoint (no `sessionId`, has `sourceSystemId`):

```
External System (LEGACY_IVR)   AuthenticateController   TransferPoliciesRegistry   AuthEngine
        |                             |                           |                    |
        | POST /ivr/authenticate     |                           |                    |
        | {sourceSystemId:"LEGACY_IVR",  |                       |                    |
        |  validatedTokens:[ACCOUNT_NUMBER],|                     |                    |
        |  currentLevel:BASIC,       |                           |                    |
        |  targetLevel:STANDARD}     |                           |                    |
        |---------------------------->|                           |                    |
        |                             | get("LEGACY_IVR")        |                    |
        |                             |-------------------------->|                    |
        |                             |   TransferPolicy         |                    |
        |                             |<--------------------------|                    |
        |                             |                           |                    |
        |                             | Filter honoredTokens     |                    |
        |                             | Cap currentLevel         |                    |
        |                             | Create IvrSession        |                    |
        |                             | (transferredFrom set)    |                    |
        |                             |                           |                    |
        |                             | transferSession(session, |                    |
        |                             |   config, honoredTokens) |                    |
        |                             |-------------------------->|                    |
        |                             |                           | evaluateProgress  |
        |  {nextRequiredToken:PIN}    |                           |                    |
        |<----------------------------|<--------------------------|                    |
```

**Processing rules:**
1. Unknown or disabled `sourceSystemId` → **403 FORBIDDEN**
2. `validatedTokens` filtered to only those in the policy's `honoredTokens`
3. `currentLevel` capped at the policy's `maxHonoredLevel`
4. Filtered tokens added to `validatedTokens`
5. Session created with `transferredFrom` set, attempt counts reset to zero
6. `evaluateProgress()` runs immediately — returns next prompt or `AUTHENTICATED`

### 6.6 AuthEngine.transferSession()

```java
public AuthenticateResponse transferSession(IvrSession session,
                                       BrandAuthConfig config,
                                       List<TokenType> validatedTokens) {
    for (TokenType tokenType : validatedTokens) {
        session.getValidatedTokens().add(tokenType);
    }
    sessionRepo.save(session);
    return evaluateProgress(session, config);
}
```

---

## 7. REST API Specification

### 7.1 Endpoints

| Method + Path | Purpose | Notes |
|---|---|---|
| `POST /ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate | Discriminated by payload fields |
| `GET /ivr/authenticate/{id}/status` | Poll current session state | For async IVR flows |
| `DELETE /ivr/authenticate/{id}` | End session (hangup) | Cleanup only |
| `GET /api/brands` | List brand configs | |
| `GET /api/brands/{id}` | Get a brand config | |
| `POST /api/brands` | Create a brand config | Validates, writes file, registers live |
| `PUT /api/brands/{id}` | Update a brand config | |
| `DELETE /api/brands/{id}` | Delete a brand config | |
| `POST /api/brands/validate` | Dry-run validate a config | |
| `POST /api/brands/{id}/clone` | Clone a brand config | |
| `GET /api/brands/{id}/export` | Export a brand config as JSON | |
| `POST /api/brands/import` | Import a brand config from JSON | |
| `GET /api/lookup-services` | List registered lookup services | |
| `GET /api/lookup-services/bindings` | Show token→service bindings | |
| `GET /api/admin/sessions` | List active sessions | |
| `GET /api/admin/sessions/search` | Search sessions | brand / status / callerId |
| `GET /api/admin/sessions/{id}` | Get session detail | |
| `DELETE /api/admin/sessions/{id}` | Force-end a session | |
| `GET /api/transfers` | List transfer policies | |
| `GET /api/transfers/{sourceSystemId}` | Get a transfer policy | |
| `PUT /api/transfers` | Replace transfer policies | Hot-reloads the registry |

**Discrimination logic** is centralized in `RequestActionDiscriminator.classify()`, which returns a `RequestAction` enum (`START`, `TRANSFER`, `SUBMIT_TOKEN`, `ESCALATE`):
- No `sessionId`, no `sourceSystemId` → `START`
- No `sessionId`, has `sourceSystemId` → `TRANSFER`
- Has `sessionId`, has `tokenType` → `SUBMIT_TOKEN`
- Has `sessionId`, no `tokenType`, has `targetLevel` → `ESCALATE`
- Has `sessionId`, no `tokenType`, no `targetLevel` → `IllegalArgumentException` (HTTP 400)

`AuthenticateRequestMapper` converts the unified `AuthenticateRequest` into the per-action DTOs (`toStartRequest`, `toTransferRequest`).

### 7.2 AuthenticateController.java

```java
@RestController
@RequestMapping("/ivr/authenticate")
@Tag(name = "IVR Authentication", description = "Unified endpoint for IVR authentication sessions")
public class AuthenticateController {

    private final AuthenticateService authenticateService;

    public AuthenticateController(AuthenticateService authenticateService) {
        this.authenticateService = authenticateService;
    }

    @PostMapping
    public ResponseEntity<AuthenticateResponse> handle(@Valid @RequestBody AuthenticateRequest req) {
        RequestAction action = RequestActionDiscriminator.classify(req);
        switch (action) {
            case TRANSFER:
                return ResponseEntity.ok(authenticateService.transfer(toTransferRequest(req)));
            case START:
                return ResponseEntity.ok(authenticateService.start(toStartRequest(req)));
            case SUBMIT_TOKEN:
                return ResponseEntity.ok(authenticateService.submitTokenWithCaller(
                    req.getSessionId(), req.getTokenType(), req.getTokenValue(), req.getCallerId()));
            case ESCALATE:
                return ResponseEntity.ok(
                    authenticateService.escalate(req.getSessionId(), req.getTargetLevel()));
            default:
                throw new IllegalStateException("Unknown action: " + action);
        }
    }

    @GetMapping("/{sessionId}/status")
    public ResponseEntity<AuthenticateResponse> status(@PathVariable String sessionId) {
        return ResponseEntity.ok(authenticateService.getStatus(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> end(@PathVariable String sessionId) {
        authenticateService.end(sessionId);
        return ResponseEntity.noContent().build();
    }
}
```

### 7.3 Request / Response DTOs

```java
// AuthenticateRequest.java — unified DTO for all post actions
@Data
public class AuthenticateRequest {
    private String sessionId;
    private String brandId;
    private String callerId;    // optional for token/escalate — enables session ownership validation
    private AuthLevel targetLevel;
    private String sourceSystemId;
    private AuthLevel currentLevel;
    private List<TokenType> validatedTokens;
    private TokenType tokenType;
    private String tokenValue;
    private Map<TokenType, String> initialTokens;
}

// AuthenticateResponse.java
@Data @Builder
public class AuthenticateResponse {
    private String          sessionId;
    private SessionStatus   status;             // COLLECTING | AUTHENTICATED | REDIRECT_TO_AGENT | FAILED | EXPIRED
    private AuthLevel       currentLevel;
    private AuthLevel       targetLevel;
    private TokenType       nextRequiredToken;  // null when AUTHENTICATED or REDIRECT_TO_AGENT
    private Integer         remainingAttempts;
    private String          prompt;             // human-readable IVR prompt text
    private Instant         lockedUntil;        // set when status=REDIRECT_TO_AGENT
    private List<TokenType> acceptedTokens;     // tokens the client may submit at this step (required + unblocked backups)
    private SessionPhase    phase;              // DISAMBIGUATION or AUTHENTICATING
    private String          matchedPartyId;     // set once party disambiguation resolves
    private List<ProcessingEvent> processingLog; // audit events: token submissions, and the start
                                                // response when levelDetermination derived the level
}
```

> **`targetLevel` on start is optional.** `StartAuthenticateRequest.targetLevel` has no `@NotNull`. For brands with a `levelDetermination` section the level is derived from call conditions and any caller-supplied value is ignored; for brands without one, omitting it is rejected (HTTP 400) because there is nothing to derive from.

---

## 8. Session Service

```java
@Service
public class AuthenticateService {

    private final AuthEngine engine;
    private final SessionRepository sessionRepo;
    private final BrandRulesRegistry rulesRegistry;
    private final TransferPoliciesRegistry transferRegistry;
    private final PartyLookupProvider partyLookup;
    private final CustomerPreferenceProvider preferenceProvider;
    private final DisambiguationEngine disambiguationEngine;
    private final LevelDeterminationEngine levelDeterminationEngine;  // derives target level from rules

    public AuthenticateService(AuthEngine engine, SessionRepository sessionRepo,
                                BrandRulesRegistry rulesRegistry,
                                TransferPoliciesRegistry transferRegistry,
                                PartyLookupProvider partyLookup,
                                CustomerPreferenceProvider preferenceProvider,
                                DisambiguationEngine disambiguationEngine,
                                LevelDeterminationEngine levelDeterminationEngine) {
        this.engine = engine;
        this.sessionRepo = sessionRepo;
        this.rulesRegistry = rulesRegistry;
        this.transferRegistry = transferRegistry;
        this.partyLookup = partyLookup;
        this.preferenceProvider = preferenceProvider;
        this.disambiguationEngine = disambiguationEngine;
        this.levelDeterminationEngine = levelDeterminationEngine;
    }

    public AuthenticateResponse start(StartAuthenticateRequest req) {
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // Brands without levelDetermination still need a caller-supplied target level.
        if (config.getLevelDetermination() == null && req.getTargetLevel() == null) {
            throw new IllegalArgumentException(
                "targetLevel is required for brand " + req.getBrandId()
                + " because it does not define levelDetermination rules");
        }

        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(AuthLevel.NONE);
        session.setTargetLevel(req.getTargetLevel() != null ? req.getTargetLevel() : AuthLevel.NONE);
        session.setStatus(SessionStatus.COLLECTING);
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        // Identification-only brands always target NONE
        if (config.isIdentificationOnly()) {
            session.setTargetLevel(AuthLevel.NONE);
        }

        // Always-on party lookup
        List<Party> parties = partyLookup.lookupByAni(req.getCallerId());
        if (parties.isEmpty()) throw new UnknownCallerException(req.getCallerId());

        session.setPhase(parties.size() > 1
            ? SessionPhase.DISAMBIGUATION : SessionPhase.AUTHENTICATING);
        session.setCandidateParties(parties);
        sessionRepo.save(session);

        if (parties.size() > 1) {
            AuthenticateResponse disResp = disambiguationEngine.start(session);
            if (session.getPhase() != SessionPhase.AUTHENTICATING) {
                return disResp;  // still narrowing parties
            }
            return proceedAfterPartyResolved(session, config, req);
        }

        // Single party — load preferences
        CustomerPreference prefs = preferenceProvider.getPreferences(
            parties.get(0).getPartyId(), session.getBrandId());
        session.setMatchedParty(parties.get(0));
        session.setCustomerPreferences(prefs);
        sessionRepo.save(session);

        return proceedAfterPartyResolved(session, config, req);
    }

    /**
     * Applies the brand's levelDetermination rules (if any) — deriving the session target
     * level from call conditions, clamped to the customer's maxAllowedLevel preference and
     * recorded as a processingLog entry — then consumes any initialTokens when appropriate,
     * otherwise hands off to the engine.
     */
    private AuthenticateResponse proceedAfterPartyResolved(IvrSession session, BrandAuthConfig config,
                                                           StartAuthenticateRequest req) {
        List<ProcessingEvent> procLog = new ArrayList<>();
        applyLevelDetermination(session, config, procLog);

        boolean consumeInitialTokens = config.isIdentificationOnly()
            ? hasNoneRule(config) && hasInitialTokens(req)
            : hasInitialTokens(req);
        AuthenticateResponse response = consumeInitialTokens
            ? processInitialTokens(session, config, req.getInitialTokens())
            : engine.onPartyResolved(session, config);

        if (!procLog.isEmpty()) {
            response.setProcessingLog(procLog);   // "Level determined: X — reason"
        }
        return response;
    }

    /**
     * Evaluates levelDetermination rules for the resolved party, overrides the session's
     * target level, clamps to the preference cap, persists, and logs the reason.
     * No-op for identification-only brands and brands without a levelDetermination section.
     */
    private void applyLevelDetermination(IvrSession session, BrandAuthConfig config,
                                         List<ProcessingEvent> procLog) {
        if (config.isIdentificationOnly() || config.getLevelDetermination() == null) return;

        DeterminationResult determination =
            levelDeterminationEngine.determine(config, session.getMatchedParty());
        if (determination == null) return;

        AuthLevel derived = determination.getLevel();
        String note = "";
        CustomerPreference prefs = session.getCustomerPreferences();
        if (prefs != null && prefs.getMaxAllowedLevel() != null
                && derived.isHigherThan(prefs.getMaxAllowedLevel())) {
            derived = prefs.getMaxAllowedLevel();
            note = " (capped by customer preference max " + prefs.getMaxAllowedLevel() + ")";
        }
        session.setTargetLevel(derived);
        sessionRepo.save(session);
        add(procLog, "INFO", "Level determined: " + derived + " — " + determination.getReason() + note);
    }

    public AuthenticateResponse transfer(CallTransferRequest req) {
        TransferPolicy policy = transferRegistry.get(req.getSourceSystemId());
        if (policy == null) throw new TransferNotAllowedException("Source system not configured");
        if (!policy.isEnabled()) throw new TransferNotAllowedException("Source system is disabled");

        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());

        // Filter validated tokens to only those honored by the policy
        List<TokenType> honoredTokens = new ArrayList<>();
        if (req.getValidatedTokens() != null) {
            for (TokenType tokenType : req.getValidatedTokens()) {
                if (transferRegistry.isTokenHonored(req.getSourceSystemId(), tokenType)) {
                    honoredTokens.add(tokenType);
                }
            }
        }

        // Cap currentLevel at the policy's maxHonoredLevel
        AuthLevel transferredLevel = req.getCurrentLevel() != null ? req.getCurrentLevel() : AuthLevel.NONE;
        AuthLevel maxHonored = transferRegistry.getMaxHonoredLevel(req.getSourceSystemId());
        if (transferredLevel.getRank() > maxHonored.getRank()) transferredLevel = maxHonored;

        IvrSession session = new IvrSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setBrandId(req.getBrandId());
        session.setCallerId(req.getCallerId());
        session.setCurrentLevel(transferredLevel);
        session.setTargetLevel(req.getTargetLevel());
        session.setStatus(SessionStatus.COLLECTING);
        session.setTransferredFrom(req.getSourceSystemId());
        session.setCreatedAt(Instant.now());
        session.setLastActivityAt(Instant.now());

        return engine.transferSession(session, config, honoredTokens);
    }

    public AuthenticateResponse submitToken(String sessionId, TokenType tokenType, String tokenValue) {
        return engine.submitToken(sessionId, tokenType, tokenValue);
    }

    public AuthenticateResponse submitTokenWithCaller(String sessionId, TokenType tokenType,
                                                       String tokenValue, String callerId) {
        return engine.submitTokenWithCaller(sessionId, tokenType, tokenValue, callerId);
    }

    public AuthenticateResponse escalate(String sessionId, AuthLevel targetLevel) {
        return engine.escalate(sessionId, targetLevel);
    }

    public AuthenticateResponse getStatus(String sessionId) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        return AuthenticateResponse.fromSession(session);
    }

    public void end(String sessionId) {
        sessionRepo.delete(sessionId);
    }
}
```

---

## 9. Session Storage (SQLite)

Sessions are stored in a SQLite database accessed via `JdbcTemplate`. The `IvrSession` object is serialized to JSON for complex fields (maps, sets, nested objects) using Jackson. A `@Scheduled` cleanup job removes expired sessions. **Optimistic locking** via a `version` column prevents lost updates from concurrent requests on the same session.

### 9.1 Database Schema

```sql
CREATE TABLE IF NOT EXISTS ivr_session (
    session_id              TEXT PRIMARY KEY,
    brand_id                TEXT NOT NULL,
    caller_id               TEXT NOT NULL,
    current_level           TEXT NOT NULL DEFAULT 'NONE',
    target_level            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'COLLECTING',
    phase                   TEXT NOT NULL DEFAULT 'AUTHENTICATING',
    collected_tokens        TEXT,       -- always NULL — sensitive values never persisted
    validated_tokens        TEXT,       -- JSON: Set<TokenType>
    attempt_counts          TEXT,       -- JSON: Map<TokenType, Integer>
    active_path_index       TEXT,       -- JSON: Map<AuthLevel, Integer>
    candidate_parties       TEXT,       -- JSON: List<Party>
    matched_party           TEXT,       -- JSON: Party
    customer_preferences    TEXT,       -- JSON: CustomerPreference
    disambiguation_attempt  INTEGER DEFAULT 0,
    version                 INTEGER NOT NULL DEFAULT 0,  -- optimistic locking
    transferred_from        TEXT,
    locked_until            TEXT,       -- ISO-8601 timestamp
    created_at              TEXT NOT NULL,
    last_activity_at        TEXT NOT NULL
);
```

### 9.2 SqliteSessionRepository

Key implementation details:

- **save()**: Dispatches to `insert()` for new sessions (`version == 0`, sets version to 1) or `update()` for existing sessions (uses `UPDATE ... WHERE session_id = ? AND version = ?`). If the update affects 0 rows, a `SessionConflictException` (HTTP 409) is thrown.
- **Sensitive data**: `collected_tokens` is always stored as `null` — raw token values (PINs, SSNs) are never persisted, only kept in memory for the duration of a single request.
- **getOrThrow()**: Single query fetches the full row and checks TTL in Java. If expired, the session is deleted and a `SessionNotFoundException` is thrown.
- **mapRow()**: Reads all 20 columns including `phase`, `candidate_parties` (JSON deserialized to `List<Party>`), `matched_party`, `customer_preferences`, `disambiguation_attempt`, `version`, `transferred_from`.
- **Cleanup**: A `@Scheduled` cleanup job runs at a fixed rate (default 60 seconds) and bulk-deletes sessions older than the configured TTL.

```java
@Repository
public class SqliteSessionRepository implements SessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration sessionTtl;

    public SqliteSessionRepository(JdbcTemplate jdbc, ObjectMapper mapper,
                                    @Value("${ivr.session.ttl-minutes:30}") int ttlMinutes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public void save(IvrSession session) {
        session.setLastActivityAt(Instant.now());
        if (session.getVersion() == 0) {
            insert(session);
        } else {
            update(session);
        }
    }

    private void insert(IvrSession session) {
        int version = 1;
        session.setVersion(version);
        jdbc.update(
            "INSERT INTO ivr_session " +
            "(session_id, brand_id, caller_id, current_level, target_level, status, " +
            "phase, collected_tokens, validated_tokens, attempt_counts, active_path_index, " +
            "candidate_parties, matched_party, customer_preferences, " +
            "disambiguation_attempt, version, transferred_from, locked_until, created_at, " +
            "last_activity_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            session.getSessionId(), session.getBrandId(), session.getCallerId(),
            session.getCurrentLevel().name(), session.getTargetLevel().name(),
            session.getStatus().name(),
            session.getPhase() != null ? session.getPhase().name() : SessionPhase.AUTHENTICATING.name(),
            null,  // collected_tokens: never persisted
            toJson(session.getValidatedTokens()),
            toJson(session.getAttemptCounts()),
            toJson(session.getActivePathIndexByLevel()),
            toJson(session.getCandidateParties()),
            toJson(session.getMatchedParty()),
            toJson(session.getCustomerPreferences()),
            session.getDisambiguationAttemptCount(),
            version,
            session.getTransferredFrom(),
            toIso(session.getLockedUntil()),
            toIso(session.getCreatedAt()),
            toIso(session.getLastActivityAt())
        );
    }

    private void update(IvrSession session) {
        int expectedVersion = session.getVersion();
        int newVersion = expectedVersion + 1;
        session.setVersion(newVersion);
        int rows = jdbc.update(
            "UPDATE ivr_session SET brand_id=?, caller_id=?, current_level=?, " +
            "target_level=?, status=?, phase=?, collected_tokens=?, validated_tokens=?, " +
            "attempt_counts=?, active_path_index=?, candidate_parties=?, " +
            "matched_party=?, customer_preferences=?, disambiguation_attempt=?, " +
            "version=?, transferred_from=?, locked_until=?, last_activity_at=? " +
            "WHERE session_id=? AND version=?",
            ... , // same fields as insert, plus WHERE clause
            session.getSessionId(), expectedVersion
        );
        if (rows == 0) throw new SessionConflictException(session.getSessionId());
    }

    @Override
    public IvrSession getOrThrow(String sessionId) {
        try {
            IvrSession session = jdbc.queryForObject(
                "SELECT * FROM ivr_session WHERE session_id = ?", this::mapRow, sessionId);
            if (session.getLastActivityAt().plus(sessionTtl).isBefore(Instant.now())) {
                delete(sessionId);
                throw new SessionNotFoundException(sessionId);
            }
            return session;
        } catch (EmptyResultDataAccessException e) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    @Scheduled(fixedRateString = "${ivr.session.cleanup.interval:60000}")
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(sessionTtl);
        int deleted = jdbc.update("DELETE FROM ivr_session WHERE last_activity_at < ?", toIso(cutoff));
    }

    private IvrSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        IvrSession s = new IvrSession();
        s.setSessionId(rs.getString("session_id"));
        s.setBrandId(rs.getString("brand_id"));
        s.setCallerId(rs.getString("caller_id"));
        s.setCurrentLevel(AuthLevel.valueOf(rs.getString("current_level")));
        s.setTargetLevel(AuthLevel.valueOf(rs.getString("target_level")));
        s.setStatus(SessionStatus.valueOf(rs.getString("status")));
        String phaseStr = rs.getString("phase");
        s.setPhase(phaseStr != null ? SessionPhase.valueOf(phaseStr) : SessionPhase.AUTHENTICATING);
        s.setCollectedTokens(fromJsonEnumMap(rs.getString("collected_tokens"), TokenType.class, String.class));
        s.setValidatedTokens(fromJsonEnumSet(rs.getString("validated_tokens"), TokenType.class));
        s.setAttemptCounts(fromJsonEnumMap(rs.getString("attempt_counts"), TokenType.class, Integer.class));
        s.setActivePathIndexByLevel(fromJsonEnumMap(rs.getString("active_path_index"), AuthLevel.class, Integer.class));
        s.setCandidateParties(fromJsonPartyList(rs.getString("candidate_parties")));
        s.setMatchedParty(fromJsonSingle(rs.getString("matched_party"), Party.class));
        s.setCustomerPreferences(fromJsonSingle(rs.getString("customer_preferences"), CustomerPreference.class));
        s.setDisambiguationAttemptCount(rs.getInt("disambiguation_attempt"));
        s.setVersion(rs.getInt("version"));
        s.setTransferredFrom(rs.getString("transferred_from"));
        s.setLockedUntil(fromIso(rs.getString("locked_until")));
        s.setCreatedAt(fromIso(rs.getString("created_at")));
        s.setLastActivityAt(fromIso(rs.getString("last_activity_at")));
        return s;
    }
    // ... JSON helpers: toJson, fromJsonEnumMap, fromJsonEnumSet, fromJsonPartyList, fromJsonSingle, toIso, fromIso
}
```

---

## 10. Key Sequence Flows

### 10.1 Normal Auth Flow (Single Brand, No Fallback)

```
IVR Platform          AuthenticateController     AuthEngine             TokenValidator
     |                       |                   |                     |
     |  POST /ivr/authenticate  |                |                     |
     | {brandId, callerId,  |                   |                     |
     |  targetLevel}        |                   |                     |
     |---------------------->|                   |                     |
     |                       |  start(req)       |                     |
     |                       |------------------>|                     |
     |                       |                   |  partyLookup(ANI)   |
     |                       |                   |  loadPreferences()  |
     |                       |                   |  evaluateProgress() |
     |  {nextToken:ACCOUNT}  |                   |                     |
     |<----------------------|                   |                     |
     |                       |                   |                     |
     |  POST /ivr/authenticate  |                |                     |
     | {sessionId, tokenType= |                   |                     |
     |  ACCOUNT_NUMBER, value} |                   |                     |
     |---------------------->|                   |                     |
     |                       |  submitToken      |  validate(ACCOUNT)  |
     |                       |------------------>|------------------->|
     |                       |                   |  {valid: true}      |
     |                       |                   |<--------------------|
     |  {nextToken:PIN}      |  evaluateProgress |                     |
     |<----------------------|<------------------|                     |
     |                       |                   |                     |
     |  POST /ivr/authenticate  |                |                     |
     | {sessionId, tokenType= |                   |                     |
     |  PIN, value}          |                   |                     |
     |---------------------->|                   |  validate(PIN)      |
     |                       |                   |------------------->|
     |                       |                   |  {valid: true}      |
     |                       |                   |<--------------------|
     |  {AUTHENTICATED}      |                   |                     |
     |<----------------------|                   |                     |
```

### 10.2 Fallback Path Triggered

```
Session targets STANDARD, primary path = [ACCOUNT_NUMBER, PIN]
PIN fails maxRetries (3 attempts) → engine switches to fallback path [ACCOUNT_NUMBER, OTP]
ACCOUNT_NUMBER is already validated and present in both paths → retained, not re-prompted
Engine prompts only for OTP

State delta:
  activePathIndexByLevel[STANDARD]: 0 → 1
  attemptCounts: cleared
  validatedTokens: {ACCOUNT_NUMBER} retained (in new path), {PIN} removed
```

### 10.3 Mid-Session Escalation Flow

```
Session is AUTHENTICATED at STANDARD (validatedTokens: {ACCOUNT_NUMBER, PIN})
IVR platform calls POST /ivr/authenticate { sessionId, targetLevel: ELEVATED }

Engine logic:
  1. Sets targetLevel = ELEVATED
  2. Calls evaluateProgress immediately
  3. ELEVATED path[0] = [ACCOUNT_NUMBER, PIN, OTP]
     ACCOUNT_NUMBER validated ✓   PIN validated ✓   OTP missing
  4. Returns { nextRequiredToken: OTP }  — only OTP is prompted

Caller enters OTP → validated → status = AUTHENTICATED, currentLevel = ELEVATED
```

### 10.4 Call Transfer Flow

```
External System (LEGACY_IVR)   AuthenticateController   TransferPoliciesRegistry   AuthEngine
       |                             |                       |                    |
       | POST /ivr/authenticate     |                       |                    |
       | {sourceSystemId:"LEGACY_IVR",|                      |                    |
       |  validatedTokens:          |                       |                    |
       |   [ACCOUNT_NUMBER],        |                       |                    |
       |  currentLevel:BASIC,       |                       |                    |
       |  targetLevel:STANDARD}     |                       |                    |
       |---------------------------->|                       |                    |
       |                             | get("LEGACY_IVR")    |                    |
       |                             |---------------------->|                    |
       |                             |   TransferPolicy      |                    |
       |                             |<----------------------|                    |
       |                             |                       |                    |
       |                             | Filter tokens against |                    |
       |                             |   honoredTokens list  |                    |
       |                             | Cap currentLevel at   |                    |
       |                             |   maxHonoredLevel     |                    |
       |                             | Create IvrSession     |                    |
       |                             | (transferredFrom set) |                    |
       |                             |                       |                    |
       |                             | transferSession()     |                    |
       |                             |---------------------->|                    |
       |                             |                       | populate validated |
       |                             |                       | evaluateProgress() |
       | {nextRequiredToken: PIN}    |                       |                    |
       |<----------------------------|<----------------------|                    |
```

State after transfer:
  sessionId = <uuid>
  brandId = "BRAND_A"
  callerId = "5551234567"
  currentLevel = BASIC (capped to maxHonoredLevel if needed)
  targetLevel = STANDARD
  status = COLLECTING
  validatedTokens = {ACCOUNT_NUMBER}  (only tokens honored by policy)
  transferredFrom = "LEGACY_IVR"
  attemptCounts = {}  (fresh start, always reset)
```

---

## 11. Exception Handling

```java
@RestControllerAdvice
public class IvrExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SessionNotFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("SESSION_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SessionLockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(SessionLockedException e) {
        return ResponseEntity.status(423)
            .body(new ErrorResponse("SESSION_REDIRECT_TO_AGENT", e.getMessage()));
    }

    @ExceptionHandler(SessionConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(SessionConflictException e) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("SESSION_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(TransferNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleTransferNotAllowed(TransferNotAllowedException e) {
        return ResponseEntity.status(403)
            .body(new ErrorResponse("TRANSFER_NOT_ALLOWED", e.getMessage()));
    }

    @ExceptionHandler(UnknownBrandException.class)
    public ResponseEntity<ErrorResponse> handleBrand(UnknownBrandException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNKNOWN_BRAND", e.getMessage()));
    }

    @ExceptionHandler(UnknownCallerException.class)
    public ResponseEntity<ErrorResponse> handleUnknownCaller(UnknownCallerException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNKNOWN_CALLER", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedTokenTypeException.class)
    public ResponseEntity<ErrorResponse> handleToken(UnsupportedTokenTypeException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNSUPPORTED_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(UnknownLookupServiceException.class)
    public ResponseEntity<ErrorResponse> handleLookupService(UnknownLookupServiceException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNKNOWN_LOOKUP_SERVICE", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("VALIDATION_ERROR", e.getMessage()));
    }

    @ExceptionHandler(SessionSerializationException.class)
    public ResponseEntity<ErrorResponse> handleSerialization(SessionSerializationException e) {
        log.error("Session serialization failure", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("INTERNAL_ERROR", "An internal error occurred"));
    }

    @ExceptionHandler(BrandConfigException.class)
    public ResponseEntity<ErrorResponse> handleBrandConfig(BrandConfigException e) {
        log.error("Brand config error", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("BRAND_CONFIG_ERROR", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(500)
            .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
```

---

## 12. Package Structure

```
com.yourco.ivr
├── api
│   ├── AuthenticateController.java         # delegates to RequestActionDiscriminator
│   ├── BrandController.java
│   ├── IvrExceptionHandler.java
│   ├── LookupServiceController.java       # GET /api/lookup-services (+ /bindings)
│   ├── SessionAdminController.java        # GET/DELETE /api/admin/sessions
│   ├── TransferPoliciesController.java    # GET/PUT /api/transfers (hot-reload)
│   ├── action/
│   │   ├── RequestAction.java              # START / TRANSFER / SUBMIT_TOKEN / ESCALATE
│   │   └── RequestActionDiscriminator.java # classifies a request by present fields
│   └── dto/
│       ├── AuthenticateRequest.java
│       ├── AuthenticateRequestMapper.java  # → StartAuthenticateRequest / CallTransferRequest
│       ├── AuthenticateResponse.java
│       ├── CallTransferRequest.java
│       ├── ErrorResponse.java
│       ├── ProcessingEvent.java
│       └── StartAuthenticateRequest.java
├── domain
│   ├── AuthLevel.java                      # NONE(0)..ADMIN(4)
│   ├── TokenType.java                      # 7 types, each with a displayName
│   ├── IvrSession.java                     # 20 fields + constructor defaults
│   ├── SessionStatus.java                  # COLLECTING..FAILED (REDIRECT_TO_AGENT replaces LOCKED)
│   ├── SessionPhase.java                   # DISAMBIGUATION / AUTHENTICATING
│   ├── Party.java                          # 8 fields + additionalAttributes
│   ├── CustomerPreference.java             # blockedTokens + maxAllowedLevel
│   └── config/
│       ├── BrandAuthConfig.java            # brandId + levelRules + identificationOnly + levelDetermination
│       ├── LevelRule.java                  # paths + maxRetriesPerToken + tokenRetryLimits + lockoutSeconds
│       ├── TokenPath.java                  # pathIndex + description + requiredTokens + backupTokens
│       ├── LevelDeterminationConfig.java   # rules + defaultLevel (declarative level selection)
│       ├── LevelSelectionRule.java         # description + level + conditions
│       ├── LevelCondition.java             # type + key + value
│       ├── LevelConditionType.java         # ANI_MATCHED | PARTY_ACTIVE | PRIMARY_ANI | PARTY_ATTRIBUTE
│       ├── TransferPolicy.java
│       └── TransferPoliciesConfig.java
├── engine
│   ├── AuthEngine.java                     # orchestrator; 8 collaborator deps
│   ├── LevelDeterminationEngine.java       # evaluates levelDetermination rules → DeterminationResult(level, reason)
│   ├── AttemptCoordinator.java             # retry/lockout coordination
│   ├── DisambiguationEngine.java           # always-on, 3-round max
│   ├── DisambiguationRule.java             # interface
│   ├── EngineConfig.java                   # wires activePathResolver + TokenSlotResolver beans
│   ├── PartyTokenFields.java               # shared TokenType → Party field accessors
│   ├── PromptResolver.java                 # human-readable token names
│   ├── impl/
│   │   ├── ExcludeInactiveRule.java
│   │   └── PrimaryAniRule.java
│   ├── path/
│   │   ├── ActivePath.java                 # rule + index + path snapshot
│   │   └── ActivePathManager.java          # path resolve/switch/exhaust + redirect-to-agent
│   ├── preference/
│   │   └── PreferenceFilter.java           # blocked-token skipping
│   ├── response/
│   │   ├── ResponseAssembler.java          # response factory methods
│   │   └── ProcessingLog.java              # processingLog builder
│   ├── slot/
│   │   └── TokenSlotResolver.java          # backup→slot mapping + accepted tokens
│   └── validation/
│       └── ExternalValidator.java          # format → party-field → backend pipeline
├── lookup                                  # NEW — backend verification layer
│   ├── TokenLookupService.java             # SPI
│   ├── LookupRequest.java / LookupResult.java
│   ├── LookupServiceRegistry.java          # auto-built from @Components
│   ├── VerificationBindings.java           # interface
│   ├── VerificationBinding.java            # serviceId + params + failClosed
│   ├── DefaultVerificationBindings.java    # in-code bindings (empty by default)
│   ├── LookupExecutor.java                 # timeout + circuit breaker
│   ├── LookupUnavailableException.java
│   └── impl/
│       └── StubLookupService.java          # always-pass dev stub
├── partylookup/
│   ├── PartyLookupProvider.java
│   └── StubPartyLookupProvider.java
├── preference/
│   ├── CustomerPreferenceProvider.java
│   └── StubCustomerPreferenceProvider.java
├── service
│   ├── AuthenticateService.java            # 7 deps, disambig wiring, initial tokens
│   └── BrandService.java
├── validator
│   ├── TokenValidator.java
│   ├── TokenValidatorRegistry.java
│   ├── TokenValidationContext.java
│   ├── ValidationResult.java / ValidationErrorCode.java
│   ├── BrandTokenValidatorOverride.java    # per-brand override support
│   └── impl/                               # 7 built-in validators:
│       ├── AccountNumberValidator.java
│       ├── CardLast4Validator.java
│       ├── DateOfBirthValidator.java
│       ├── OtpTokenValidator.java
│       ├── PinValidator.java
│       ├── SsnLast4Validator.java
│       └── VoicePrintValidator.java
├── registry
│   ├── BrandRulesRegistry.java
│   ├── BrandRulesLoader.java
│   └── TransferPoliciesRegistry.java
├── repository
│   ├── DatabaseConfig.java
│   ├── SessionRepository.java
│   └── SqliteSessionRepository.java        # insert/update with optimistic locking
└── exception/                              # 10 custom exceptions:
    ├── BrandConfigException.java
    ├── SessionConflictException.java
    ├── SessionLockedException.java
    ├── SessionNotFoundException.java
    ├── SessionSerializationException.java
    ├── TransferNotAllowedException.java
    ├── UnknownBrandException.java
    ├── UnknownCallerException.java
    ├── UnknownLookupServiceException.java
    └── UnsupportedTokenTypeException.java
```

---

## 13. Party Disambiguation

Party disambiguation is always-on. Every session start triggers `PartyLookupProvider.lookupByAni()` to identify the caller's party. The caller's ANI (phone number) may map to multiple customer records; the engine resolves ambiguity before authentication proceeds.

### 13.1 Party Domain Model

```java
// Party.java
@Data
public class Party {
    private String partyId;          // unique identifier
    private String accountNumber;    // maps to ACCOUNT_NUMBER token
    private String dateOfBirth;      // maps to DATE_OF_BIRTH token
    private String ssnLast4;         // maps to SSN_LAST4 token
    private String cardLast4;        // maps to CARD_LAST4 token
    private String zipCode;          // additional disambiguation field
    private boolean active;          // active customer
    private boolean primaryAni;      // this ANI is the primary contact
    private Map<String, String> additionalAttributes; // extensibility
}
```

### 13.2 PartyLookupProvider (Interface)

```java
public interface PartyLookupProvider {
    List<Party> lookupByAni(String callerId);
}
```

A stub implementation (`StubPartyLookupProvider`) returns a single active party per caller (`partyId = "STUB-<callerId>"`, `accountNumber = callerId`, `active = true`, `primaryAni = true`). Replace with a real implementation that queries your CRM/account system.

### 13.3 DisambiguationRule (Interface)

```java
public interface DisambiguationRule {
    List<Party> apply(List<Party> parties);
}
```

**Built-in rules:**

| Rule Type | Class | Behavior |
|-----------|-------|----------|
| `EXCLUDE_INACTIVE` | `ExcludeInactiveRule` | Removes parties where `active == false` |
| `PREFER_PRIMARY_ANI` | `PrimaryAniRule` | Keeps only parties where `primaryAni == true`; if none, keeps all |

These rules are **not configurable** — they are a fixed chain applied in order by every brand. There is no `disambiguation` block in the brand JSON.

### 13.4 DisambiguationEngine

```java
@Service
public class DisambiguationEngine {

    // Disambiguation is always-on and not configurable.
    private static final int MAX_DISAMBIGUATION_TOKENS = 3;

    // Fixed pre-filter rule chain, applied in order
    private final List<DisambiguationRule> rules;   // [ExcludeInactiveRule, PrimaryAniRule]

    private final SessionRepository sessionRepo;
    private final CustomerPreferenceProvider preferenceProvider;  // loaded on party resolution

    public DisambiguationEngine(SessionRepository sessionRepo,
                                CustomerPreferenceProvider preferenceProvider) {
        this.sessionRepo = sessionRepo;
        this.preferenceProvider = preferenceProvider;
        this.rules = Arrays.asList(new ExcludeInactiveRule(), new PrimaryAniRule());
    }

    /** Called on session start to initialize disambiguation. */
    AuthenticateResponse start(IvrSession session);

    /** Called when a token is submitted during disambiguation phase. */
    AuthenticateResponse handleToken(IvrSession session, TokenType tokenType, String tokenValue);

    /** Selects the token that best differentiates remaining parties. */
    TokenType selectDisambiguationToken(List<Party> parties);

    /** Applies the fixed filtering rule chain. */
    List<Party> applyRules(List<Party> parties);
}
```

The token-to-`Party`-field mapping is no longer a private field; it is the shared `PartyTokenFields.FIELD_ACCESSORS` map (`ACCOUNT_NUMBER`, `DATE_OF_BIRTH`, `SSN_LAST4`, `CARD_LAST4`), used by both token selection and value matching. On resolving a single party, `resolveParty()` sets `matchedParty`, transitions the phase to `AUTHENTICATING`, and loads customer preferences via `preferenceProvider`.

### 13.5 Disambiguation Flow

```
Session Start with ANI
       │
       ▼
PartyLookupProvider.lookupByAni(ANI)
       │
  ┌────┼────┐
  ▼    ▼    ▼
  0    1    N parties
  │    │    │
  ▼    │    ▼
 400   │   ┌──────────────────┐
Error  │   │ Apply rules       │── EXCLUDE_INACTIVE
       │   │                   │── PREFER_PRIMARY_ANI
       │   └──────────────────┘
       │    │
       │    ├── 1 party → matchedParty set
       │    │
       │    └── N parties → selectDisambiguationToken()
       │         │
       │         ▼
       │   Return prompt: "Please provide your {token}"
       │         │
       │         ▼
       │   Customer submits token
       │         │
       │         ▼
       │   Match against party attributes
       │         │
       │    ┌────┴─────┐
       │    1          N          0
       │    │          │          │
       │    └──────────┼──────────┘
       │               ▼
       │        matchedParty set
       │               │
       └───────────────┘
               ▼
      CustomerPreferenceProvider.getPreferences(partyId)
               │
               ▼
      Phase → AUTHENTICATING
      AuthEngine.evaluateProgress()
```

### 13.6 Token Selection Strategy

The engine evaluates each mappable `TokenType` (ACCOUNT_NUMBER, DATE_OF_BIRTH, SSN_LAST4, CARD_LAST4) against the remaining parties. It groups parties by the token's value and returns the token with the smallest largest group (maximum discrimination).

Example: 3 parties, SSN_LAST4 values: {1234, 5678, 9012} → groups of size 1 each → selected.  
If all parties have identical SSN_LAST4 → group size 3 → not selected if a better token exists.

**Max rounds:** Capped at a fixed `MAX_DISAMBIGUATION_TOKENS = 3`. After exhausting, the session is marked FAILED.

### 13.7 Session Phase Model

```java
// SessionPhase.java
public enum SessionPhase {
    DISAMBIGUATION,   // resolving multiple parties to one
    AUTHENTICATING    // standard auth flow (may include preference filtering)
}
```

Sessions start in `DISAMBIGUATION` when >1 parties found. Phase transitions to `AUTHENTICATING` once `matchedParty` is set.

- 0 parties → `UnknownCallerException` (HTTP 400)
- 1 party → immediate transition to `AUTHENTICATING`, preferences loaded
- N parties → disambiguation tokens requested until resolved or max rounds exceeded

---

## 14. Customer Preferences

Once a single party is identified (either immediately or via disambiguation), customer-specific preferences are loaded to personalize the authentication experience.

### 14.1 CustomerPreference Domain Model

```java
// CustomerPreference.java
@Data
public class CustomerPreference {
    private Set<TokenType> blockedTokens;   // tokens to NEVER ask for
    private AuthLevel maxAllowedLevel;      // cap auth level for this customer
}
```

### 14.2 CustomerPreferenceProvider (Interface)

```java
public interface CustomerPreferenceProvider {
    CustomerPreference getPreferences(String partyId, String brandId);
}
```

A stub implementation (`StubCustomerPreferenceProvider`) returns an empty `CustomerPreference` (no blocks). Replace with a real implementation.

### 14.3 AuthEngine Preference Filtering

When `CustomerPreference` is present and `blockedTokens` is non-empty, the engine applies filtering in `evaluateProgress()`:

1. After determining `nextToken` from the active path, check `isBlocked(session, nextToken)`
2. If blocked → try `findAlternativeToken()` (unblocked backups)
3. If all alternatives blocked → `advanceToNextPathOrFail()`
4. `buildAcceptedTokens()` excludes blocked tokens from the accepted list

```java
// AuthEngine helper methods
private boolean isBlocked(IvrSession session, TokenType tokenType) {
    CustomerPreference prefs = session.getCustomerPreferences();
    return prefs != null && prefs.getBlockedTokens() != null
        && prefs.getBlockedTokens().contains(tokenType);
}

private TokenType findAlternativeToken(IvrSession session,
                                        TokenPath path, TokenType blocked) {
    // Returns first unblocked backup, or null if all blocked
}
```

**Example:** Customer has PIN blocked.
- Path 0 requires [ACCOUNT_NUMBER, PIN] with backup {PIN: [SSN_LAST4, DATE_OF_BIRTH]}
- After ACCOUNT_NUMBER validated, nextToken = PIN
- `isBlocked(PIN)` → true
- `findAlternativeToken()` → SSN_LAST4 (if unblocked)
- Prompt asks for SSN_LAST4 instead of PIN

**Example:** Customer has PIN, SSN_LAST4, and DATE_OF_BIRTH all blocked.
- All options on path 0 blocked → advance to path 1 (OTP)
- Path 1 requires [ACCOUNT_NUMBER, OTP] — ACCOUNT_NUMBER already validated → prompt for OTP

### 14.4 Preference Loading Trigger

Preferences are loaded by `DisambiguationEngine.resolveParty()` immediately after a single party is identified:

```java
private AuthenticateResponse resolveParty(IvrSession session, Party party) {
    session.setMatchedParty(party);
    session.setPhase(SessionPhase.AUTHENTICATING);
    CustomerPreference prefs = preferenceProvider.getPreferences(
        party.getPartyId(), session.getBrandId());
    session.setCustomerPreferences(prefs);
    sessionRepo.save(session);
    return buildResponse(session, "Identity verified. Proceeding with authentication.", null);
}
```

### 14.5 Data Flow

```
Party Resolved
     │
     ▼
CustomerPreferenceProvider.getPreferences(partyId, brandId)
     │
     ▼
session.setCustomerPreferences(prefs)
     │
     ▼
AuthEngine.evaluateProgress() checks:
  → isBlocked(nextToken)? try backups → try next path → fail
  → buildAcceptedTokens() excludes blocked
     │
     ▼
Customer asked only for allowed tokens
```

---

> ⚠️ **Security Note — Token Values**
> - Never log raw token values (PINs, OTPs, SSN digits). Log only `tokenType` and validation outcome.
> - Raw token values are never persisted to the database — `collected_tokens` column is always stored as `null`. Values exist in memory only for the duration of a single request.
> - Use HTTPS in production; token values are submitted via the API.
