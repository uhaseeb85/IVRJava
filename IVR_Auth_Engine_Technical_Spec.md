# IVR Token Authentication Engine
### Multi-Brand | Progressive Auth Levels | Rule-Driven
**Technical Implementation Document — Version 1.4 | Java 8 | Spring Boot 2.7.x**

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Domain Model](#2-domain-model)
3. [Brand Configuration (JSON)](#3-brand-configuration-json)
4. [Auth Engine — Core Logic](#4-auth-engine--core-logic)
5. [Token Validator Layer](#5-token-validator-layer)
6. [Cross-Brand Token Sharing](#6-cross-brand-token-sharing)
7. [Call Transfer](#7-call-transfer)
8. [REST API Specification](#8-rest-api-specification)
9. [Session Service](#9-session-service)
10. [Session Storage (SQLite)](#10-session-storage-sqlite)
11. [Key Sequence Flows](#11-key-sequence-flows)
12. [Exception Handling](#12-exception-handling)
13. [Package Structure](#13-package-structure)
14. [Party Disambiguation](#14-party-disambiguation)
15. [Customer Preferences](#15-customer-preferences)
16. [Implementation Checklist](#16-implementation-checklist)

---

## 1. System Overview

This document specifies the design and implementation of a multi-brand IVR Token Authentication Engine built on Java 8 and Spring Boot 2.7.x. The system allows multiple brands to define independent authentication rule sets, share token validators, and optionally share validated tokens across sessions. Callers can target a specific auth level, upgrade mid-session, and reuse previously validated tokens.

### 1.1 Core Capabilities

- **Multi-brand rule isolation** — each brand carries its own level definitions, token paths, retry limits, and lockout policies
- **Shared token validators** — a global registry of validators (OTP, PIN, account number, voice, etc.) any brand can reference
- **Token sharing policies** — a brand can declare that tokens validated in another brand's context are reusable within the same session
- **Progressive authentication** — a session starts at `NONE`, reaches targeted levels step by step, and can escalate further without restarting
- **Declarative rules** — all behaviour is driven by JSON config; no logic changes require code deployments
- **Call transfer support** — external IVR systems can transfer callers mid-authentication with pre-validated tokens; per-source policies control which tokens and auth levels are honored

### 1.2 High-Level Architecture

| Layer | Responsibility |
|---|---|
| REST API | Accepts IVR platform calls; maps HTTP to session commands |
| Auth Engine | Core state machine; evaluates rules, drives path progression |
| Rules Registry | Loads and serves brand-specific `AuthRuleSet` objects from JSON on classpath |
| Validator Registry | Maps token types to their `TokenValidator` implementations |
| Transfer Policies Registry | Loads and serves per-source `TransferPolicy` objects from external JSON |
| Session Store | SQLite-backed via JdbcTemplate; holds `IvrSession` with full token/level state |
| External APIs | Called per-token by validators; results cached at session scope |

---

## 2. Domain Model

### 2.1 Core Enums

```java
// TokenType.java
public enum TokenType {
    ACCOUNT_NUMBER, PIN, OTP, SSN_LAST4, VOICE_PRINT, DATE_OF_BIRTH, CARD_LAST4
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
    private String brandId;                         // e.g. "BRAND_A"
    private Map<AuthLevel, LevelRule> levelRules;   // one rule per level
    private TokenSharingPolicy sharingPolicy;       // cross-brand token reuse
}

// LevelRule.java
@Data
public class LevelRule {
    private AuthLevel level;
    private List<TokenPath> paths;    // [0]=primary, [1..n]=fallbacks
    private int maxRetriesPerToken;   // applies to each token in every path
    private int lockoutSeconds;       // 0 = no lockout
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

### 2.3 Token Sharing Policy

```java
// TokenSharingPolicy.java
@Data
public class TokenSharingPolicy {
    // Tokens that are universally trusted across any brand context
    private Set<TokenType> globallySharedTokens;

    // Tokens accepted only when validated in specific other brands
    // Key: TokenType, Value: set of brandIds whose validation is trusted
    private Map<TokenType, Set<String>> conditionallySharedFrom;

    // Optional: max age in seconds for a cross-brand token to still be trusted
    private int crossBrandTokenMaxAgeSeconds;  // 0 = no TTL
}
```

### 2.4 Session State

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

    // Tokens collected this session (raw values — encrypt at rest)
    private Map<TokenType, String> collectedTokens;

    // Tokens fully validated — key to auth decisions
    private Set<TokenType> validatedTokens;

    // Tracks per-token attempt counts within the active path
    private Map<TokenType, Integer> attemptCounts;

    // Active path index per level (allows independent path tracking)
    private Map<AuthLevel, Integer> activePathIndexByLevel;

    // Cross-brand token provenance: token → (brandId, validatedAt timestamp)
    private Map<TokenType, CrossBrandTokenRecord> crossBrandTokens;

    // Source system that transferred this call (null if session started locally)
    private String transferredFrom;

    private Instant lockedUntil;
    private Instant createdAt;
    private Instant lastActivityAt;
}

public enum SessionStatus {
    COLLECTING, VALIDATING, AUTHENTICATED, LOCKED, EXPIRED, FAILED
}

@Data @AllArgsConstructor
public class CrossBrandTokenRecord {
    private String sourceBrandId;
    private Instant validatedAt;
}
```

---

## 3. Brand Configuration (JSON)

Each brand's rule set lives in its own JSON file loaded at startup from the classpath (`resources/brands/`). Brand IDs must match exactly what the IVR platform sends in session start requests.

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
  "sharingPolicy": {
    "globallySharedTokens": ["ACCOUNT_NUMBER"],
    "conditionallySharedFrom": {
      "OTP": ["BRAND_B", "BRAND_C"],
      "PIN": ["BRAND_B"]
    },
    "crossBrandTokenMaxAgeSeconds": 1800
  }
}
```

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
  },
  "sharingPolicy": {
    "globallySharedTokens": ["ACCOUNT_NUMBER"],
    "conditionallySharedFrom": {},
    "crossBrandTokenMaxAgeSeconds": 0
  }
}
```

---

## 4. Auth Engine — Core Logic

The `AuthEngine` is the heart of the system. It is stateless itself; all state is passed via `IvrSession`. This enables horizontal scaling with no affinity requirements.

### 4.1 AuthEngine.java

```java
@Service
@RequiredArgsConstructor
public class AuthEngine {

    private final BrandRulesRegistry     rulesRegistry;
    private final TokenValidatorRegistry validatorRegistry;
    private final SessionRepository      sessionRepo;
    private final CrossBrandTokenEvaluator crossBrandEvaluator;

    /** Called when the IVR platform submits a token value. */
    public AuthenticateResponse submitToken(String sessionId,
                                       TokenType tokenType,
                                       String tokenValue) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());

        // 1. Lockout guard
        if (isLocked(session)) return buildLockedResponse(session);

        // 2. Check cross-brand token shortcut before calling external API
        boolean valid = crossBrandEvaluator.isAccepted(session, config, tokenType, tokenValue)
            || validateExternally(session, tokenType, tokenValue);

        if (!valid) return handleFailure(session, config, tokenType);

        // 3. Mark validated and record cross-brand provenance
        session.getValidatedTokens().add(tokenType);
        session.getAttemptCounts().remove(tokenType);  // reset on success
        crossBrandEvaluator.recordValidated(session, tokenType);

        // 4. Evaluate progress toward targetLevel
        return evaluateProgress(session, config);
    }

    /** Request to reach a higher auth level (mid-session upgrade). */
    public AuthenticateResponse escalate(String sessionId, AuthLevel newTarget) {
        IvrSession session = sessionRepo.getOrThrow(sessionId);
        AuthLevel current = session.getCurrentLevel();

        if (!newTarget.isHigherThan(current))
            throw new IllegalArgumentException("Target must exceed current level");

        session.setTargetLevel(newTarget);
        sessionRepo.save(session);

        // Re-evaluate immediately — existing tokens may already satisfy the new level
        BrandAuthConfig config = rulesRegistry.get(session.getBrandId());
        return evaluateProgress(session, config);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    AuthenticateResponse evaluateProgress(IvrSession session, BrandAuthConfig config) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        int activePathIdx = session.getActivePathIndexByLevel()
            .getOrDefault(session.getTargetLevel(), 0);
        TokenPath activePath = rule.getPaths().get(activePathIdx);

        // Check if active path is fully satisfied
        boolean pathComplete = activePath.getRequiredTokens().stream()
            .allMatch(t -> session.getValidatedTokens().contains(t));

        if (pathComplete) {
            session.setCurrentLevel(session.getTargetLevel());
            session.setStatus(SessionStatus.AUTHENTICATED);
            sessionRepo.save(session);
            return AuthenticateResponse.authenticated(session);
        }

        // Find next missing token on this path
        TokenType nextToken = activePath.getRequiredTokens().stream()
            .filter(t -> !session.getValidatedTokens().contains(t))
            .findFirst().orElseThrow();

        session.setStatus(SessionStatus.COLLECTING);
        sessionRepo.save(session);
        return AuthenticateResponse.collect(session, nextToken, rule.getMaxRetriesPerToken());
    }

    private AuthenticateResponse handleFailure(IvrSession session,
                                           BrandAuthConfig config,
                                           TokenType tokenType) {
        LevelRule rule = config.getLevelRules().get(session.getTargetLevel());
        Map<TokenType, Integer> counts = session.getAttemptCounts();
        int attempts = counts.merge(tokenType, 1, Integer::sum);
        int remaining = rule.getMaxRetriesPerToken() - attempts;

        if (remaining > 0) {
            sessionRepo.save(session);
            return AuthenticateResponse.retry(session, tokenType, remaining);
        }

        // Try advancing to next fallback path
        int currentPathIdx = session.getActivePathIndexByLevel()
            .getOrDefault(session.getTargetLevel(), 0);
        int nextPathIdx = currentPathIdx + 1;

        if (nextPathIdx < rule.getPaths().size()) {
            session.getActivePathIndexByLevel().put(session.getTargetLevel(), nextPathIdx);
            session.getAttemptCounts().clear();
            pruneTokensNotInPath(session, rule.getPaths().get(nextPathIdx));
            sessionRepo.save(session);

            TokenType nextToken = rule.getPaths().get(nextPathIdx)
                .getRequiredTokens().stream()
                .filter(t -> !session.getValidatedTokens().contains(t))
                .findFirst().orElseThrow();
            return AuthenticateResponse.fallback(session, nextToken);
        }

        // All paths exhausted → lock
        session.setStatus(SessionStatus.LOCKED);
        session.setLockedUntil(Instant.now().plusSeconds(rule.getLockoutSeconds()));
        sessionRepo.save(session);
        return AuthenticateResponse.locked(session);
    }

    private void pruneTokensNotInPath(IvrSession session, TokenPath newPath) {
        Set<TokenType> keep = new HashSet<>(newPath.getRequiredTokens());
        session.getValidatedTokens().retainAll(keep);
    }

    private boolean isLocked(IvrSession session) {
        return session.getStatus() == SessionStatus.LOCKED
            && session.getLockedUntil() != null
            && Instant.now().isBefore(session.getLockedUntil());
    }

    private boolean validateExternally(IvrSession session,
                                        TokenType tokenType,
                                        String tokenValue) {
        TokenValidator validator = validatorRegistry.resolve(session.getBrandId(), tokenType);
        TokenValidationContext ctx = new TokenValidationContext(
            tokenType, tokenValue, session.getCallerId(),
            session.getCollectedTokens(), session.getBrandId()
        );
        return validator.validate(ctx).isValid();
    }
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
    INVALID, EXPIRED, NOT_FOUND, RATE_LIMITED, EXTERNAL_ERROR
}
```

### 5.2 Sample Validator — OTP

```java
@Component
public class OtpTokenValidator implements TokenValidator {

    private final OtpServiceClient otpClient;

    @Override
    public TokenType supportedType() { return TokenType.OTP; }

    @Override
    public ValidationResult validate(TokenValidationContext ctx) {
        try {
            OtpVerifyResponse resp = otpClient.verify(
                ctx.getCallerId(),
                ctx.getTokenValue(),
                ctx.getBrandId()
            );
            return resp.isValid()
                ? ValidationResult.ok()
                : ValidationResult.fail(resp.isExpired() ? EXPIRED : INVALID);
        } catch (ExternalApiException e) {
            log.error("OTP validation error", e);
            return ValidationResult.fail(EXTERNAL_ERROR);  // fail closed
        }
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

---

## 6. Cross-Brand Token Sharing

The sharing policy allows a brand to trust tokens validated in another brand's context within the same session. This is resolved before making any external API call — avoiding duplicate validation round-trips.

### 6.1 CrossBrandTokenEvaluator.java

```java
@Component
@RequiredArgsConstructor
public class CrossBrandTokenEvaluator {

    public boolean isAccepted(IvrSession session,
                              BrandAuthConfig config,
                              TokenType tokenType,
                              String tokenValue) {
        TokenSharingPolicy policy = config.getSharingPolicy();
        if (policy == null) return false;

        // 1. Globally shared — trusted from any brand
        if (policy.getGloballySharedTokens().contains(tokenType)) {
            return session.getValidatedTokens().contains(tokenType);
        }

        // 2. Conditionally shared — trust only from listed brands
        Set<String> trustedBrands = policy.getConditionallySharedFrom()
            .getOrDefault(tokenType, Set.of());

        CrossBrandTokenRecord record = session.getCrossBrandTokens().get(tokenType);
        if (record == null) return false;
        if (!trustedBrands.contains(record.getSourceBrandId())) return false;

        // 3. TTL check
        int maxAge = policy.getCrossBrandTokenMaxAgeSeconds();
        if (maxAge > 0) {
            Instant expiry = record.getValidatedAt().plusSeconds(maxAge);
            if (Instant.now().isAfter(expiry)) return false;
        }

        return true;
    }

    /** Record a token validated in this session for potential reuse by other brands. */
    public void recordValidated(IvrSession session, TokenType type) {
        session.getCrossBrandTokens().put(type,
            new CrossBrandTokenRecord(session.getBrandId(), Instant.now()));
    }
}
```

> **Design Note — Token Value vs Token Presence**
> Cross-brand sharing checks presence in `validatedTokens`, not the raw value. If a different value is submitted for an already-validated globally-shared token, it is re-validated externally. This prevents a caller from submitting a wrong value and still getting credit via the sharing shortcut.

---

## 7. Call Transfer

Call transfer allows an external IVR/authentication system to hand off a caller mid-authentication to this engine. The caller's pre-validated tokens and achieved auth level are carried over, so the caller does not re-authenticate tokens they have already provided.

### 7.1 Transfer Policies

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

### 7.2 Transfer Policy Model

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

### 7.3 TransferPoliciesRegistry

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

### 7.4 Transfer Request DTO

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

### 7.5 Transfer Flow

```
External System (LEGACY_IVR)    TransferController     TransferPoliciesRegistry     AuthEngine
        |                              |                           |                    |
        | POST /ivr/authenticate/transfer   |                           |                    |
        | {source:"LEGACY_IVR",        |                           |                    |
        |  validated:[ACCOUNT_NUMBER],  |                           |                    |
        |  currentLevel:BASIC,          |                           |                    |
        |  targetLevel:STANDARD}        |                           |                    |
        |----------------------------->|                           |                    |
        |                              |  get("LEGACY_IVR")        |                    |
        |                              |-------------------------->|                    |
        |                              |     TransferPolicy        |                    |
        |                              |<--------------------------|                    |
        |                              |                           |                    |
        |                              | Filter honoredTokens      |                    |
        |                              | Cap currentLevel          |                    |
        |                              | Create IvrSession         |                    |
        |                              |                           |                    |
        |                              | transferSession(session,  |                    |
        |                              |   config, honoredTokens)  |                    |
        |                              |-------------------------->|                    |
        |  {nextRequiredToken:PIN}     |  evaluateProgress         |                    |
        |<-----------------------------|<--------------------------|                    |
```

**Processing rules:**
1. Unknown or disabled `sourceSystemId` → **403 FORBIDDEN**
2. `validatedTokens` filtered to only those in the policy's `honoredTokens`
3. `currentLevel` capped at the policy's `maxHonoredLevel`
4. Filtered tokens added to both `validatedTokens` and `crossBrandTokens` (with source = `sourceSystemId`)
5. Session created with `transferredFrom` set, attempt counts reset to zero
6. `evaluateProgress()` runs immediately — returns next prompt or `AUTHENTICATED`

### 7.6 AuthEngine.transferSession()

```java
public AuthenticateResponse transferSession(IvrSession session,
                                       BrandAuthConfig config,
                                       List<TokenType> validatedTokens,
                                       String sourceSystemId) {
    Instant now = Instant.now();
    for (TokenType tokenType : validatedTokens) {
        session.getValidatedTokens().add(tokenType);
        session.getCrossBrandTokens().put(tokenType,
            new CrossBrandTokenRecord(sourceSystemId, now));
    }
    sessionRepo.save(session);
    return evaluateProgress(session, config);
}
```

---

## 8. REST API Specification

### 8.1 Endpoints

| Method + Path | Purpose | Notes |
|---|---|---|
| `POST /ivr/authenticate` | Unified endpoint — start, transfer, submit token, or escalate | Discriminated by payload fields |
| `GET /ivr/authenticate/{id}/status` | Poll current session state | For async IVR flows |
| `DELETE /ivr/authenticate/{id}` | End session (hangup) | Cleanup only |

**Discrimination logic:**
- No `sessionId`, no `sourceSystemId` → START
- No `sessionId`, has `sourceSystemId` → TRANSFER
- Has `sessionId`, has `tokenType` → TOKEN
- Has `sessionId`, no `tokenType`, has `targetLevel` → ESCALATE

### 8.2 AuthenticateController.java

```java
@RestController
@RequestMapping("/ivr/authenticate")
public class AuthenticateController {

    private final AuthenticateService sessionService;

    @PostMapping
    public ResponseEntity<AuthenticateResponse> handle(@RequestBody SessionRequest req) {
        if (req.getSessionId() == null) {
            if (req.getSourceSystemId() != null) {
                return transfer(req);   // builds CallTransferRequest
            }
            return start(req);          // builds StartAuthenticateRequest
        }
        if (req.getTokenType() != null) {
            return token(req);          // → sessionService.submitToken()
        }
        return escalate(req);           // → sessionService.escalate()
    }

    @GetMapping("/{sessionId}/status")
    public ResponseEntity<AuthenticateResponse> status(@PathVariable String sessionId);

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> end(@PathVariable String sessionId);
}
```

### 8.3 Request / Response DTOs

```java
// AuthenticateRequest.java — unified DTO for all post actions
@Data
public class AuthenticateRequest {
    private String sessionId;
    private String brandId;
    private String callerId;
    private AuthLevel targetLevel;
    private String sourceSystemId;
    private AuthLevel currentLevel;
    private List<TokenType> validatedTokens;
    private TokenType tokenType;
    private String tokenValue;
    private Map<TokenType, CrossBrandTokenRecord> crossBrandTokens;
    private Map<TokenType, String> initialTokens;
}

// AuthenticateResponse.java
@Data @Builder
public class AuthenticateResponse {
    private String          sessionId;
    private SessionStatus   status;
    private AuthLevel       currentLevel;
    private AuthLevel       targetLevel;
    private TokenType       nextRequiredToken;  // null when AUTHENTICATED or LOCKED
    private Integer         remainingAttempts;
    private String          prompt;             // human-readable IVR prompt text
    private Instant         lockedUntil;        // set when status=LOCKED
    private List<TokenType> acceptedTokens;     // tokens the client may submit at this step
}
```

---

## 9. Session Service

```java
@Service
@RequiredArgsConstructor
public class AuthenticateService {

    private final AuthEngine         engine;
    private final SessionRepository  sessionRepo;
    private final BrandRulesRegistry rulesRegistry;

    public AuthenticateResponse start(StartAuthenticateRequest req) {
        BrandAuthConfig config = rulesRegistry.get(req.getBrandId());
        if (config == null)
            throw new UnknownBrandException(req.getBrandId());

        IvrSession session = IvrSession.builder()
            .sessionId(UUID.randomUUID().toString())
            .brandId(req.getBrandId())
            .callerId(req.getCallerId())
            .currentLevel(AuthLevel.NONE)
            .targetLevel(req.getTargetLevel())
            .status(SessionStatus.COLLECTING)
            .collectedTokens(new EnumMap<>(TokenType.class))
            .validatedTokens(EnumSet.noneOf(TokenType.class))
            .attemptCounts(new EnumMap<>(TokenType.class))
            .activePathIndexByLevel(new EnumMap<>(AuthLevel.class))
            .crossBrandTokens(Optional.ofNullable(req.getCrossBrandTokens())
                .orElse(new EnumMap<>(TokenType.class)))
            .createdAt(Instant.now())
            .lastActivityAt(Instant.now())
            .build();

        sessionRepo.save(session);

        // Evaluate immediately — cross-brand tokens may already satisfy some levels
        return engine.evaluateProgress(session, config);
    }

    public AuthenticateResponse submitToken(String id, TokenType type, String value) {
        return engine.submitToken(id, type, value);
    }

    public AuthenticateResponse escalate(String id, AuthLevel target) {
        return engine.escalate(id, target);
    }

    public AuthenticateResponse getStatus(String id) {
        IvrSession session = sessionRepo.getOrThrow(id);
        return AuthenticateResponse.fromSession(session);
    }

    public void end(String id) {
        sessionRepo.delete(id);
    }
}
```

---

## 10. Session Storage (SQLite)

Sessions are stored in a SQLite database accessed via `JdbcTemplate`. The `IvrSession` object is serialized to JSON for complex fields (maps, sets, nested objects) using Jackson. A `@Scheduled` cleanup job removes expired sessions.

### 10.1 Database Schema

```sql
CREATE TABLE IF NOT EXISTS ivr_session (
    session_id              TEXT PRIMARY KEY,
    brand_id                TEXT NOT NULL,
    caller_id               TEXT NOT NULL,
    current_level           TEXT NOT NULL DEFAULT 'NONE',
    target_level            TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'COLLECTING',
    collected_tokens        TEXT,       -- JSON: Map<TokenType, String>
    validated_tokens        TEXT,       -- JSON: Set<TokenType>
    attempt_counts          TEXT,       -- JSON: Map<TokenType, Integer>
    active_path_index       TEXT,       -- JSON: Map<AuthLevel, Integer>
    cross_brand_tokens      TEXT,       -- JSON: Map<TokenType, CrossBrandTokenRecord>
    transferred_from        TEXT,       -- Source system ID if call was transferred
    locked_until            TEXT,       -- ISO-8601 timestamp
    created_at              TEXT NOT NULL,
    last_activity_at        TEXT NOT NULL
);
```

### 10.2 SqliteSessionRepository

```java
@Repository
@RequiredArgsConstructor
public class SqliteSessionRepository implements SessionRepository {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public void save(IvrSession session) {
        session.setLastActivityAt(Instant.now());
        String sql = "INSERT OR REPLACE INTO ivr_session " +
            "(session_id, brand_id, caller_id, current_level, target_level, status, " +
            "collected_tokens, validated_tokens, attempt_counts, active_path_index, " +
            "cross_brand_tokens, locked_until, created_at, last_activity_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql,
            session.getSessionId(),
            session.getBrandId(),
            session.getCallerId(),
            session.getCurrentLevel().name(),
            session.getTargetLevel().name(),
            session.getStatus().name(),
            toJson(session.getCollectedTokens()),
            toJson(session.getValidatedTokens()),
            toJson(session.getAttemptCounts()),
            toJson(session.getActivePathIndexByLevel()),
            toJson(session.getCrossBrandTokens()),
            toIso(session.getLockedUntil()),
            toIso(session.getCreatedAt()),
            toIso(session.getLastActivityAt())
        );
    }

    @Override
    public IvrSession getOrThrow(String sessionId) {
        String sql = "SELECT * FROM ivr_session WHERE session_id = ?";
        try {
            return jdbc.queryForObject(sql, new Object[]{sessionId}, this::mapRow);
        } catch (EmptyResultDataAccessException e) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    @Override
    public void delete(String sessionId) {
        jdbc.update("DELETE FROM ivr_session WHERE session_id = ?", sessionId);
    }

    /** Scheduled cleanup of expired sessions. */
    @Scheduled(fixedRateString = "${ivr.session.cleanup.interval:60000}")
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        jdbc.update("DELETE FROM ivr_session WHERE last_activity_at < ?", toIso(cutoff));
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private IvrSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        IvrSession s = new IvrSession();
        s.setSessionId(rs.getString("session_id"));
        s.setBrandId(rs.getString("brand_id"));
        s.setCallerId(rs.getString("caller_id"));
        s.setCurrentLevel(AuthLevel.valueOf(rs.getString("current_level")));
        s.setTargetLevel(AuthLevel.valueOf(rs.getString("target_level")));
        s.setStatus(SessionStatus.valueOf(rs.getString("status")));
        s.setCollectedTokens(fromJsonMap(rs.getString("collected_tokens"), TokenType.class, String.class));
        s.setValidatedTokens(fromJsonSet(rs.getString("validated_tokens"), TokenType.class));
        s.setAttemptCounts(fromJsonMap(rs.getString("attempt_counts"), TokenType.class, Integer.class));
        s.setActivePathIndexByLevel(fromJsonMap(rs.getString("active_path_index"), AuthLevel.class, Integer.class));
        s.setCrossBrandTokens(fromJsonCrossBrand(rs.getString("cross_brand_tokens")));
        s.setLockedUntil(fromIso(rs.getString("locked_until")));
        s.setCreatedAt(fromIso(rs.getString("created_at")));
        s.setLastActivityAt(fromIso(rs.getString("last_activity_at")));
        return s;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new SessionSerializationException(e); }
    }

    private <K, V> Map<K, V> fromJsonMap(String json, Class<K> keyType, Class<V> valueType) {
        if (json == null || json.isEmpty()) return new EnumMap<>((Class<K>)keyType);
        try {
            return mapper.readValue(json,
                mapper.getTypeFactory().constructMapType(EnumMap.class, keyType, valueType));
        } catch (IOException e) { throw new SessionSerializationException(e); }
    }

    private <T> Set<T> fromJsonSet(String json, Class<T> elementType) {
        if (json == null || json.isEmpty()) return EnumSet.noneOf((Class<T>)elementType);
        try {
            return mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(EnumSet.class, elementType));
        } catch (IOException e) { throw new SessionSerializationException(e); }
    }

    private Map<TokenType, CrossBrandTokenRecord> fromJsonCrossBrand(String json) {
        if (json == null || json.isEmpty()) return new EnumMap<>(TokenType.class);
        try {
            return mapper.readValue(json,
                mapper.getTypeFactory().constructMapType(EnumMap.class,
                    TokenType.class, CrossBrandTokenRecord.class));
        } catch (IOException e) { throw new SessionSerializationException(e); }
    }

    private String toIso(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private Instant fromIso(String iso) {
        return iso != null ? Instant.parse(iso) : null;
    }
}
```

---

## 11. Key Sequence Flows

### 11.1 Normal Auth Flow (Single Brand, No Fallback)

```
IVR Platform          AuthenticateController     AuthEngine             ExternalAPI
     |                       |                   |                     |
     |  POST /session/start  |                   |                     |
     |---------------------->|                   |                     |
     |                       |  start(req)       |                     |
     |                       |------------------>|                     |
     |  {nextToken:ACCOUNT}  |  evaluateProgress |                     |
     |<----------------------|<------------------|                     |
     |                       |                   |                     |
     |  POST /token ACCOUNT  |                   |                     |
     |---------------------->|                   |                     |
     |                       |  submitToken      |  validate(ACCOUNT)  |
     |                       |------------------>|------------------->|
     |                       |                   |  {valid: true}      |
     |                       |                   |<--------------------|
     |  {nextToken:PIN}      |  evaluateProgress |                     |
     |<----------------------|<------------------|                     |
     |                       |                   |                     |
     |  POST /token PIN      |                   |                     |
     |---------------------->|                   |  validate(PIN)      |
     |                       |                   |------------------->|
     |                       |                   |  {valid: true}      |
     |                       |                   |<--------------------|
     |  {AUTHENTICATED}      |                   |                     |
     |<----------------------|                   |                     |
```

### 11.2 Fallback Path Triggered

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

### 11.3 Mid-Session Escalation Flow

```
Session is AUTHENTICATED at STANDARD (validatedTokens: {ACCOUNT_NUMBER, PIN})
IVR platform calls POST /session/{id}/escalate { targetLevel: ELEVATED }

Engine logic:
  1. Sets targetLevel = ELEVATED
  2. Calls evaluateProgress immediately
  3. ELEVATED path[0] = [ACCOUNT_NUMBER, PIN, OTP]
     ACCOUNT_NUMBER validated ✓   PIN validated ✓   OTP missing
  4. Returns { nextRequiredToken: OTP }  — only OTP is prompted

Caller enters OTP → validated → status = AUTHENTICATED, currentLevel = ELEVATED
```

### 11.4 Cross-Brand Token Reuse

```
Brand A session already validated OTP.
crossBrandTokens records: { OTP → { sourceBrandId: "BRAND_A", validatedAt: T } }

New session starts for Brand B, passes crossBrandTokens in the start request.

Brand B sharingPolicy does NOT list OTP as conditionallySharedFrom Brand A:
  → CrossBrandTokenEvaluator returns false
  → OTP validator called externally as normal

Brand C sharingPolicy lists OTP as conditionallySharedFrom: [BRAND_A], TTL = 1800s:
  → CrossBrandTokenEvaluator checks TTL, returns true
  → OTP marked validated — no external API call made
```

### 11.5 Call Transfer Flow

```
External System (LEGACY_IVR)   AuthenticateController   TransferPoliciesRegistry   AuthEngine
       |                             |                       |                    |
       | POST /ivr/authenticate/transfer  |                       |                    |
       | {source:"LEGACY_IVR",       |                       |                    |
       |  validated:[ACCOUNT_NUMBER],|                       |                    |
       |  currentLevel:BASIC,        |                       |                    |
       |  targetLevel:STANDARD}      |                       |                    |
       |---------------------------->|                       |                    |
       |                             |  get("LEGACY_IVR")    |                    |
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
       |                             |                       | & crossBrandTokens |
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
  crossBrandTokens = {ACCOUNT_NUMBER -> {sourceBrandId: "LEGACY_IVR", validatedAt: T}}
  transferredFrom = "LEGACY_IVR"
  attemptCounts = {}  (fresh start, always reset)
```

---

## 12. Exception Handling

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
            .body(new ErrorResponse("SESSION_LOCKED", e.getMessage()));
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

    @ExceptionHandler(UnsupportedTokenTypeException.class)
    public ResponseEntity<ErrorResponse> handleToken(UnsupportedTokenTypeException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("UNSUPPORTED_TOKEN", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }
}
```

---

## 13. Package Structure

```
com.yourco.ivr
├── api
│   ├── AuthenticateController.java
│   └── dto/
│       ├── CallTransferRequest.java
│       ├── StartAuthenticateRequest.java
│       ├── TokenSubmitRequest.java
│       ├── EscalateRequest.java
│       └── AuthenticateResponse.java
├── domain
│   ├── AuthLevel.java
│   ├── TokenType.java
│   ├── IvrSession.java
│   ├── SessionStatus.java
│   ├── CrossBrandTokenRecord.java
│   └── config/
│       ├── BrandAuthConfig.java
│       ├── LevelRule.java
│       ├── TokenPath.java
│       ├── TokenSharingPolicy.java
│       ├── TransferPolicy.java
│       └── TransferPoliciesConfig.java
├── engine
│   ├── AuthEngine.java
│   ├── CrossBrandTokenEvaluator.java
│   └── PromptResolver.java
├── service
│   ├── AuthenticateService.java
│   └── BrandService.java
├── validator
│   ├── TokenValidator.java
│   ├── TokenValidatorRegistry.java
│   ├── TokenValidationContext.java
│   ├── ValidationResult.java
│   └── impl/
│       ├── AccountNumberValidator.java
│       ├── PinValidator.java
│       ├── OtpTokenValidator.java
│       ├── VoicePrintValidator.java
│       └── SsnLast4Validator.java
├── registry
│   ├── BrandRulesRegistry.java
│   ├── BrandRulesLoader.java
│   └── TransferPoliciesRegistry.java
├── repository
│   ├── DatabaseConfig.java
│   ├── SessionRepository.java
│   └── SqliteSessionRepository.java
└── exception/
    ├── SessionNotFoundException.java
    ├── SessionLockedException.java
    ├── SessionSerializationException.java
    ├── TransferNotAllowedException.java
    ├── UnknownBrandException.java
    └── UnsupportedTokenTypeException.java
```

---

## 14. Party Disambiguation

Party disambiguation is always-on. Every session start triggers `PartyLookupProvider.lookupByAni()` to identify the caller's party. The caller's ANI (phone number) may map to multiple customer records; the engine resolves ambiguity before authentication proceeds.

### 14.1 Party Domain Model

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

### 14.2 PartyLookupProvider (Interface)

```java
public interface PartyLookupProvider {
    List<Party> lookupByAni(String callerId);
}
```

A stub implementation (`StubPartyLookupProvider`) returns an empty list by default. Replace with a real implementation that queries your CRM/account system.

### 14.3 DisambiguationRule (Interface)

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

Rules are configured per-brand in the brand JSON. The `disambiguation` block is optional; defaults apply if absent:

```json
{
  "disambiguation": {
    "maxDisambiguationTokens": 3,
    "rules": [
      { "type": "EXCLUDE_INACTIVE" },
      { "type": "PREFER_PRIMARY_ANI" }
    ]
  }
}
```

### 14.4 DisambiguationEngine

```java
@Service
public class DisambiguationEngine {

    // Maps TokenType to Party field extractors for matching
    private static final Map<TokenType, Function<Party, String>> TOKEN_FIELD_MAP;

    static {
        // ACCOUNT_NUMBER → Party::getAccountNumber
        // DATE_OF_BIRTH → Party::getDateOfBirth
        // SSN_LAST4 → Party::getSsnLast4
        // CARD_LAST4 → Party::getCardLast4
    }

    /** Called on session start to initialize disambiguation. */
    AuthenticateResponse start(IvrSession session, DisambiguationConfig config);

    /** Called when a token is submitted during disambiguation phase. */
    AuthenticateResponse handleToken(IvrSession session, TokenType tokenType,
                                 String tokenValue, DisambiguationConfig config);

    /** Selects the token that best differentiates remaining parties. */
    TokenType selectDisambiguationToken(List<Party> parties);

    /** Applies configured filtering rules. */
    List<Party> applyRules(List<Party> parties, DisambiguationConfig config);
}
```

### 14.5 Disambiguation Flow

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

### 14.6 Token Selection Strategy

The engine evaluates each mappable `TokenType` (ACCOUNT_NUMBER, DATE_OF_BIRTH, SSN_LAST4, CARD_LAST4) against the remaining parties. It groups parties by the token's value and returns the token with the smallest largest group (maximum discrimination).

Example: 3 parties, SSN_LAST4 values: {1234, 5678, 9012} → groups of size 1 each → selected.  
If all parties have identical SSN_LAST4 → group size 3 → not selected if a better token exists.

**Max rounds:** Capped at `maxDisambiguationTokens` (default 3). After exhausting, the session is marked FAILED.

### 14.7 Session Phase Model

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

## 15. Customer Preferences

Once a single party is identified (either immediately or via disambiguation), customer-specific preferences are loaded to personalize the authentication experience.

### 15.1 CustomerPreference Domain Model

```java
// CustomerPreference.java
@Data
public class CustomerPreference {
    private Set<TokenType> blockedTokens;   // tokens to NEVER ask for
    private AuthLevel maxAllowedLevel;      // cap auth level for this customer
}
```

### 15.2 CustomerPreferenceProvider (Interface)

```java
public interface CustomerPreferenceProvider {
    CustomerPreference getPreferences(String partyId, String brandId);
}
```

A stub implementation (`StubCustomerPreferenceProvider`) returns an empty `CustomerPreference` (no blocks). Replace with a real implementation.

### 15.3 AuthEngine Preference Filtering

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

### 15.4 Preference Loading Trigger

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

### 15.5 Data Flow

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

## 16. Implementation Checklist

### Phase 1 — Core Foundation
1. Define `AuthLevel` and `TokenType` enums
2. Implement domain model: `IvrSession`, `BrandAuthConfig`, `LevelRule`, `TokenPath`, `TokenSharingPolicy`
3. Implement JSON loader: `BrandRulesLoader` reads `/brands/*.json`, populates `BrandRulesRegistry`
4. Implement `SessionRepository` with SQLite (JdbcTemplate) and Jackson serialization
5. Write unit tests for domain model serialization round-trips

### Phase 2 — Auth Engine
1. Implement `AuthEngine.evaluateProgress()` — path satisfaction check
2. Implement `AuthEngine.handleFailure()` — retry countdown and path fallback switch
3. Implement `AuthEngine.escalate()` — target upgrade with incremental token reuse
4. Implement `pruneTokensNotInPath()` — token retention logic across path switch
5. Unit test all engine branches: success, retry, fallback, lockout, escalation

### Phase 3 — Validators
1. Implement `TokenValidator` interface and `ValidationResult`
2. Implement stub validators for each `TokenType` (return configurable mock responses)
3. Wire `TokenValidatorRegistry` with Spring auto-discovery of `@Component` validators
4. Implement `CrossBrandTokenEvaluator` — global share, conditional share, TTL checks
5. Replace stubs with real external API clients one validator at a time

### Phase 4 — API Layer
1. Implement `AuthenticateController` and all 5 endpoints
2. Implement `AuthenticateService` wiring engine and session repo
3. Implement `IvrExceptionHandler` with appropriate HTTP status codes
4. Add request validation (`@Valid`, `@NotNull`, `@NotBlank`) on all DTOs
5. Integration-test each endpoint with MockMvc against in-memory session store

### Phase 5 — Multi-Brand
1. Create JSON configs for each brand under `resources/brands/`
2. Test that unknown `brandId` returns 400 with `UNKNOWN_BRAND` error code
3. Test cross-brand token sharing: globally shared, conditionally shared, TTL expiry
4. Test that a brand with no sharing policy never accepts cross-brand tokens

### Phase 6 — Hardening
1. Encrypt token values at rest in SQLite (AES-GCM, per-session key)
2. Add distributed rate limiting per `callerId` to back the per-token retry limits
3. Add session TTL enforcement: reject requests on expired sessions with `410 Gone`
4. Add structured audit logging: `sessionId`, `brandId`, `tokenType`, result — **never log token values**
5. Load-test the SQLite session store under expected concurrent IVR call volume

### Phase 7 — Call Transfer
1. Implement `TransferPolicy`, `TransferPoliciesConfig` domain models
2. Implement `TransferPoliciesRegistry` with JSON loading from `./config/transfers/`
3. Implement `CallTransferRequest` DTO with `@Valid` constraints
4. Implement `TransferNotAllowedException` and wire into `IvrExceptionHandler` (HTTP 403)
5. Add `transferredFrom` field to `IvrSession`, DB schema, and repository
6. Implement `AuthEngine.transferSession()` — populate validated/cross-brand tokens from transfer
7. Implement `POST /ivr/authenticate/transfer` endpoint in `AuthenticateController`
8. Wire token filtering and level capping in `AuthenticateService.transfer()`
9. Create default `config/transfers/transfer-policies.json` with sample policies
10. Integration-test: happy path, token filtering, level capping, unknown source, disabled source

### Phase 8 — Party Disambiguation & Customer Preferences
1. Implement `Party`, `SessionPhase`, `CustomerPreference` domain models
2. Implement `DisambiguationConfig` config model
3. Implement `PartyLookupProvider` interface and `StubPartyLookupProvider`
4. Implement `DisambiguationRule` interface, `ExcludeInactiveRule`, `PrimaryAniRule`
5. Implement `DisambiguationEngine` — rule application, token selection, party matching
6. Implement `CustomerPreferenceProvider` interface and `StubCustomerPreferenceProvider`
7. Add `phase`, `candidateParties`, `matchedParty`, `customerPreferences`, `disambiguationAttemptCount` to `IvrSession`
8. Update DB schema and `SqliteSessionRepository` for new fields
9. Add `DisambiguationConfig` to `BrandAuthConfig` and brand JSON files
10. Wire party lookup and disambiguation into `AuthenticateService.start()`
11. Route `DISAMBIGUATION` phase in `AuthEngine.submitToken()` to `DisambiguationEngine`
12. Add preference filtering in `AuthEngine.evaluateProgress()` and `buildAcceptedTokens()`
13. Add `phase` and `matchedPartyId` to `AuthenticateResponse`
14. Implement `UnknownCallerException` and wire into `IvrExceptionHandler`
15. Integration-test: single party, multi-party, rules narrowing, zero parties, max rounds, disabled config, preference filtering

> ⚠️ **Security Note — Token Values**
> Never log raw token values (PINs, OTPs, SSN digits). Log only `tokenType` and validation outcome.
> Store `collectedTokens` encrypted in SQLite using a per-session AES key, itself wrapped with a KMS key.
