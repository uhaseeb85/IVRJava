# IVR Auth Engine — Maintenance Guide

A practical reference for the most common maintenance tasks: adding brands, adding token types, adjusting auth levels, wiring disambiguation rules, and swapping stub implementations for real ones.

---

## Contents

1. [What needs code vs what doesn't](#1-what-needs-code-vs-what-doesnt)
2. [Adding a new brand](#2-adding-a-new-brand)
3. [Brand config structure reference](#3-brand-config-structure-reference)
4. [Modifying an existing brand](#4-modifying-an-existing-brand)
5. [Adding a new token type](#5-adding-a-new-token-type)
6. [Adding a new auth level](#6-adding-a-new-auth-level)
7. [Adding a new disambiguation rule](#7-adding-a-new-disambiguation-rule)
8. [Brand-specific validator overrides](#8-brand-specific-validator-overrides)
9. [Adding a transfer policy](#9-adding-a-transfer-policy)
10. [Configuring phone risk policies](#10-configuring-phone-risk-policies)
11. [Replacing stubs with real implementations](#11-replacing-stubs-with-real-implementations)
12. [Testing requirements](#12-testing-requirements)
13. [Keeping docs in sync](#13-keeping-docs-in-sync)

---

## 1. What needs code vs what doesn't

| Task | Code change? | How |
|---|---|---|
| Add a new brand | ❌ No | JSON file or UI or API |
| Change a brand's token paths / retries / lockout | ❌ No | Edit JSON file or use UI |
| Add a risk policy for a brand | ❌ No | Add `riskPolicies` block to the brand JSON |
| Add a disambiguation rule type | ✅ Yes | Implement `DisambiguationRule` + register in engine |
| Add a transfer policy | ❌ No | Edit `config/transfers/transfer-policies.json` |
| Add a new token type | ✅ Yes | Enum + validator + prompt text |
| Add a new auth level | ✅ Yes | Enum rank + update brand configs |
| Brand-specific validation logic | ✅ Yes | `BrandTokenValidatorOverride` Spring bean |
| Replace stub party lookup / preferences | ✅ Yes | Implement the interface + remove `@Component` from stub |
| Replace stub phone risk provider | ✅ Yes | Implement `PhoneRiskProvider` + remove `@Component` from stub |

---

## 2. Adding a new brand

You have three options — all are equivalent at runtime.

### Option A — Brand Config Editor UI (easiest)

1. Start the app: `mvn spring-boot:run`
2. Open `http://localhost:8081/`
3. Click **New Brand**, fill in the form, save.

The UI writes directly to `config/brands/<brandId>.json` and reloads the registry.

### Option B — REST API

```bash
curl -X POST http://localhost:8081/api/brands \
  -H "Content-Type: application/json" \
  -d '{
    "brandId": "BRAND_C",
    "levelRules": {
      "BASIC": {
        "paths": [
          { "pathIndex": 0, "description": "Account lookup",
            "requiredTokens": ["ACCOUNT_NUMBER"] }
        ],
        "maxRetriesPerToken": 3,
        "lockoutSeconds": 300
      }
    }
  }'
```

### Option C — Drop a JSON file directly

1. Create `config/brands/brand_c.json` (file name must be `<brandId>.toLowerCase() + ".json"`).
2. The engine picks it up automatically on the next `PUT /api/brands/{id}` call or restart. To force an immediate reload without restarting, use:

```bash
curl -X PUT http://localhost:8081/api/brands/BRAND_C \
  -H "Content-Type: application/json" \
  -d @config/brands/brand_c.json
```

> **Validation rules enforced on every save:**
> - `brandId` must be non-empty
> - `levelRules` must have at least one entry
> - Every level must have at least one path
> - Every path must have at least one required token

---

## 3. Brand config structure reference

```json
{
  "brandId": "BRAND_C",

  "disambiguation": {
    "maxDisambiguationTokens": 3,
    "rules": [
      { "type": "EXCLUDE_INACTIVE" },
      { "type": "PREFER_PRIMARY_ANI" }
    ]
  },

  "levelRules": {
    "BASIC": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Human-readable label (shown in processing log)",
          "requiredTokens": ["ACCOUNT_NUMBER"]
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    },

    "STANDARD": {
      "paths": [
        {
          "pathIndex": 0,
          "description": "Primary: Account + PIN",
          "requiredTokens": ["ACCOUNT_NUMBER", "PIN"],
          "backupTokens": {
            "PIN": ["SSN_LAST4", "DATE_OF_BIRTH"]
          }
        },
        {
          "pathIndex": 1,
          "description": "Fallback: Account + OTP",
          "requiredTokens": ["ACCOUNT_NUMBER", "OTP"]
        }
      ],
      "maxRetriesPerToken": 3,
      "lockoutSeconds": 300
    }
  }
}
```

### Key rules

| Field | Notes |
|---|---|
| `brandId` | Must match the file name (`brand_c.json` → `"BRAND_C"`). Case-insensitive on disk. |
| `levelRules` keys | Must be valid `AuthLevel` enum values: `BASIC`, `STANDARD`, `ELEVATED`, `ADMIN` |
| `paths` | Ordered list. `paths[0]` is primary; `paths[1..n]` are fallbacks activated when retries on the current path are exhausted. |
| `requiredTokens` | Ordered. The engine collects them left to right. |
| `backupTokens` | Map from a required token → list of alternatives the caller may submit instead. The engine accepts any of them but maps them back to the required slot internally. |
| `maxRetriesPerToken` | Failures per required-token slot before switching to the next path (or locking if all paths exhausted). |
| `lockoutSeconds` | How long the session is locked after all paths fail. |
| `disambiguation` | Optional. Defaults to `maxDisambiguationTokens: 3`, no rules. |

---

## 4. Modifying an existing brand

### Via UI
Open `http://localhost:8081/`, find the brand, edit in the **Rules** or **JSON** tab, save.

### Via API
```bash
curl -X PUT http://localhost:8081/api/brands/BRAND_A \
  -H "Content-Type: application/json" \
  -d @config/brands/brand_a.json
```

### Directly on disk
Edit `config/brands/brand_a.json`, then PUT to the API to reload, or restart the service.

> Validation is enforced on every save — invalid configs are rejected with a 400 and a descriptive error message.

---

## 5. Adding a new token type

This requires four code changes. No other files are affected.

### Step 1 — Add to the `TokenType` enum

`src/main/java/com/yourco/ivr/domain/TokenType.java`

```java
public enum TokenType {
    ACCOUNT_NUMBER, PIN, OTP, SSN_LAST4, VOICE_PRINT, DATE_OF_BIRTH, CARD_LAST4,
    SECURITY_QUESTION   // ← add here
}
```

### Step 2 — Create a validator

Create `src/main/java/com/yourco/ivr/validator/impl/SecurityQuestionValidator.java`:

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

### Step 3 — Add a human-readable prompt name

`src/main/java/com/yourco/ivr/engine/PromptResolver.java` — add a case to `tokenName()`:

```java
case SECURITY_QUESTION: return "security question answer";
```

### Step 4 — Add to DisambiguationEngine (only if it can identify a party)

If this token can differentiate between parties (i.e. your Party object carries the answer), add it to the `tokenFieldMap` in `DisambiguationEngine.java`:

```java
// in defaultTokenFieldMap():
map.put(TokenType.SECURITY_QUESTION, Party::getSecurityAnswer);
```

And add a case to `formatTokenName()` in the same class:

```java
case SECURITY_QUESTION: return "security question answer";
```

If the token is only used for auth (not disambiguation), skip Step 4.

### Step 5 — Use it in brand configs

```json
"requiredTokens": ["ACCOUNT_NUMBER", "SECURITY_QUESTION"]
```

No restart needed if you update only the JSON. A restart is required for the enum + validator code changes.

---

## 6. Adding a new auth level

Auth levels have a fixed rank order. Adding one requires touching two places.

### Step 1 — Add to the `AuthLevel` enum

`src/main/java/com/yourco/ivr/domain/AuthLevel.java`

```java
public enum AuthLevel {
    NONE(0), BASIC(1), STANDARD(2), ELEVATED(3), ADMIN(4),
    SUPER_ADMIN(5);   // ← add with next rank
    ...
}
```

Ranks must be unique and ordered correctly — `isHigherThan()` compares them numerically.

### Step 2 — Add rules to the brands that need it

Add a `SUPER_ADMIN` entry to `levelRules` in whichever brand configs require it. Brands that don't define a level simply cannot be escalated to it (the engine throws `IllegalArgumentException`).

### No other code changes needed.

---

## 7. Adding a new disambiguation rule

Disambiguation rules filter the candidate party list before token-based resolution begins.

### Step 1 — Implement the interface

Create `src/main/java/com/yourco/ivr/engine/impl/PreferBusinessAccountRule.java`:

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

Note: **do not** annotate with `@Component` — rules are instantiated on demand by the engine.

### Step 2 — Register the rule type string

`src/main/java/com/yourco/ivr/engine/DisambiguationEngine.java` — add a case to `createRule()`:

```java
case "PREFER_BUSINESS_ACCOUNT":
    return new PreferBusinessAccountRule();
```

### Step 3 — Use it in brand configs

```json
"disambiguation": {
  "rules": [
    { "type": "EXCLUDE_INACTIVE" },
    { "type": "PREFER_BUSINESS_ACCOUNT" }
  ]
}
```

Rules are applied in order, so put broader filters first.

---

## 8. Brand-specific validator overrides

Sometimes one brand needs stricter or different validation for a token type (e.g. BRAND_B validates PINs against a different backend). Use `BrandTokenValidatorOverride`.

### Example — custom PIN validator for BRAND_B

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
            public com.yourco.ivr.validator.ValidationResult validate(
                    TokenValidationContext ctx) {
                // Call your real backend here
                boolean ok = myBackend.verifyPin(ctx.getCallerId(), ctx.getTokenValue());
                return ok ? ValidationResult.ok()
                          : ValidationResult.fail(ValidationErrorCode.INVALID);
            }
        });
    }
}
```

`TokenValidatorRegistry` automatically picks up all `BrandTokenValidatorOverride` beans. When a session belongs to `BRAND_B` and submits `PIN`, the override is used. All other brands and token types still use the default validators.

---

## 9. Adding a transfer policy

Transfer policies control which external systems can transfer calls in and what they're allowed to bring with them.

Edit `config/transfers/transfer-policies.json`:

```json
{
  "policies": [
    {
      "sourceSystemId": "NEW_SYSTEM",
      "honoredTokens": ["ACCOUNT_NUMBER", "PIN"],
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
| `maxHonoredLevel` | The caller's claimed auth level is capped at this value, regardless of what the source system reports |
| `enabled` | Set to `false` to disable without removing the config |

> **Transfer policies require a restart to reload.** Unlike brand configs, they are not hot-reloadable.

---

## 10. Configuring phone risk policies

Risk policies let each brand gate or modify sessions based on the caller's ANI risk score — **no code change required** once the `PhoneRiskProvider` is wired.

### How it works

1. At session start (and on call transfer), `PhoneRiskProvider.assess(callerId, brandId)` is called.
2. The result's `RiskLevel` (`LOW` / `MEDIUM` / `HIGH` / `CRITICAL`) is looked up in the brand's `riskPolicies` map.
3. If a matching policy is found, it is applied before any session state is written.

### Policy fields

| Field | Type | Behavior |
|---|---|---|
| `reject` | boolean | `true` → return **HTTP 403** (`HIGH_RISK_CALLER`). No session is created. |
| `minimumTargetLevel` | `AuthLevel` | If the requested `targetLevel` is lower, the engine silently upgrades it. |
| `blockedTokens` | list of `TokenType` | Merged with the customer's own blocked tokens. These types are never offered or accepted for this session. |

### JSON example

Add a `riskPolicies` block to any brand's JSON file (or via the UI JSON tab):

```json
{
  "brandId": "BRAND_A",
  "riskPolicies": {
    "HIGH": {
      "reject": false,
      "minimumTargetLevel": "ELEVATED",
      "blockedTokens": ["DATE_OF_BIRTH"]
    },
    "CRITICAL": {
      "reject": true
    }
  },
  "levelRules": { ... }
}
```

Risk levels with no entry (e.g. `LOW`, `MEDIUM` above) are unrestricted.

### Interaction with customer preferences

`blockedTokens` from the risk policy are **merged** with the customer's own `blockedTokens` loaded from `CustomerPreferenceProvider`. The union of both sets is applied, so a caller flagged HIGH who also has a customer-level block on `PIN` will have both `DATE_OF_BIRTH` and `PIN` blocked.

### Seeing risk level in responses

`riskLevel` is always present in the `AuthenticateResponse`, even when no risk policy applied. The IVR platform can use this to log, route to an agent, or apply its own UI logic without changing the engine.

### Testing risk policies

Use `@MockBean PhoneRiskProvider` in your integration tests (see `PhoneRiskTest.java` for examples):

```java
@MockBean
private PhoneRiskProvider riskProvider;

@Test
void criticalCallerIsRejected() {
    when(riskProvider.assess(anyString(), anyString()))
        .thenReturn(assessment(RiskLevel.CRITICAL));
    ResponseEntity<ErrorResponse> resp = rest.postForEntity(
        "/ivr/authenticate", startReq(AuthLevel.STANDARD), ErrorResponse.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}
```

---

## 11. Replacing stubs with real implementations

The engine ships with three stub implementations that always return fixed data. Replace them before going to production.

### PartyLookupProvider — looks up parties by ANI

1. Create your implementation:

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

2. Remove `@Component` from `StubPartyLookupProvider` (or delete it).

The engine handles the three outcomes automatically:
- **0 parties** → 400 `UNKNOWN_CALLER`
- **1 party** → proceeds to auth
- **N parties** → enters disambiguation flow

### CustomerPreferenceProvider — loads per-customer token preferences

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

### PhoneRiskProvider — scores ANI risk at session start

```java
package com.yourco.ivr.partyrisk;

import com.yourco.ivr.domain.RiskAssessment;
import com.yourco.ivr.domain.RiskLevel;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Primary   // or remove @Component from StubPhoneRiskProvider
public class CarrierPhoneRiskProvider implements PhoneRiskProvider {

    private final FraudApiClient fraudApi;

    public CarrierPhoneRiskProvider(FraudApiClient fraudApi) {
        this.fraudApi = fraudApi;
    }

    @Override
    public RiskAssessment assess(String callerId, String brandId) {
        FraudApiResponse resp = fraudApi.score(callerId);
        RiskAssessment assessment = new RiskAssessment();
        assessment.setLevel(mapLevel(resp.getScore()));
        assessment.setFlags(resp.getFlags()); // e.g. ["RECENTLY_PORTED", "SPOOFED_ANI"]
        return assessment;
    }

    private RiskLevel mapLevel(int score) {
        if (score >= 90) return RiskLevel.CRITICAL;
        if (score >= 70) return RiskLevel.HIGH;
        if (score >= 40) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
```

The assessment result is stored on the session and returned as `riskLevel` in every `AuthenticateResponse`. Brand `riskPolicies` (configured in JSON, see §10) determine what action to take — no code change required for policy adjustments.

---

## 12. Testing requirements

**Every new feature or engine behavior change requires a new test.** Tests live in `src/test/java/com/yourco/ivr/` and are `@SpringBootTest` integration tests using `TestRestTemplate`.

```bash
mvn test                          # run all tests
mvn test -Dtest=AuthEngineTest    # run a specific class
```

### What to test for new tokens

- Happy path: submit the new token, session reaches `AUTHENTICATED`
- Failure path: submit wrong value N times, verify lockout or path switch
- Backup path: if the new token is a backup for another, verify it's accepted

### What to test for new brands

- Start a session, verify correct `nextRequiredToken` is returned
- Complete the primary path, verify `AUTHENTICATED`
- Exhaust retries, verify fallback path activates

### Stub data

`StubPartyLookupProvider` returns one party per callerId with:
- `partyId = "STUB-<callerId>"`
- `accountNumber = callerId`
- `active = true`, `primaryAni = true`

`StubCustomerPreferenceProvider` returns empty preferences (no blocked tokens, no level cap). Override these in tests using `@MockBean` if you need specific scenarios.

`StubPhoneRiskProvider` returns `RiskLevel.LOW` for every caller. Override it with `@MockBean PhoneRiskProvider` in tests that exercise risk policy behaviour (see `PhoneRiskTest.java`).

### What to test for new risk policies

- `CRITICAL` risk → verify HTTP 403 and `HIGH_RISK_CALLER` error code
- `HIGH` risk with `minimumTargetLevel` → verify `targetLevel` in response is upgraded
- `HIGH` risk with `blockedTokens` → verify blocked token does not appear in `nextRequiredToken` or `acceptedTokens`
- Risk level with no policy defined → verify session proceeds normally
- `riskLevel` field is present in every start response

---

## 13. Keeping docs in sync

The CLAUDE.md project rules require these two files to be updated alongside any code or config change:

| Document | Update when |
|---|---|
| `README.md` | Endpoints change, new config options added, startup instructions change |
| `IVR_Auth_Engine_Technical_Spec.md` | Engine behavior changes, new auth level, new token type, new API shape |

This file (`MAINTENANCE_GUIDE.md`) should be updated when:
- A new `DisambiguationRule` type is added (document it in §7)
- The stubs are replaced (update §11 to reflect the real implementation)
- A new auth level or token type is shipped (update the tables in §1 and §5/§6)
- New risk policy behaviour is added (update §10)
