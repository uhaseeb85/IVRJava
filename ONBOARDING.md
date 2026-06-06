# Brand Onboarding Guide

How to add a new brand to the IVR Token Authentication Engine.

---

## TL;DR — do I need to write code?

| Scenario | What it takes |
|---|---|
| **Dev / test brand** reusing the built-in token types | **Pure configuration.** One JSON file (or a few clicks in the UI). ~5 minutes. **No Java.** |
| **Production brand** reusing the built-in token types | Configuration **+** the real customer-lookup integration. That integration is written **once for the whole engine**, not per brand — if it already exists, your new brand is again pure config. |
| Brand needs a **token type that doesn't exist yet** | Small Java addition (a new `TokenValidator`). Rare. |
| Brand needs **different validation rules** for an existing token | A small per-brand `BrandTokenValidatorOverride` bean. |

**Bottom line:** Onboarding a brand is configuration. Code is only needed once, at the framework level, to connect real customer data — and that work is shared by every brand.

---

## 1. What a brand config is

A brand is a single JSON file at `config/brands/{brandId}.json`. The shape mirrors the Java model in
[`src/main/java/com/yourco/ivr/domain/config/`](src/main/java/com/yourco/ivr/domain/config/):

```
BrandAuthConfig
├── brandId                          # unique id, e.g. "BRAND_C"
├── levelRules: Map<AuthLevel, LevelRule>
│   └── LevelRule
│       ├── paths: List<TokenPath>   # paths[0] = primary, paths[1..n] = fallbacks
│       │   └── TokenPath
│       │       ├── pathIndex        # 0-based position
│       │       ├── description      # human label
│       │       ├── requiredTokens   # all must validate to complete the path
│       │       └── backupTokens     # optional: required token -> alternatives
│       ├── maxRetriesPerToken       # failed attempts allowed before fallback
│       └── lockoutSeconds           # lockout duration after exhausting retries
└── disambiguation (optional)        # how to resolve one ANI -> many parties
    ├── maxDisambiguationTokens
    └── rules                        # EXCLUDE_INACTIVE, PREFER_PRIMARY_ANI
```

**Auth levels** ([`AuthLevel`](src/main/java/com/yourco/ivr/domain/AuthLevel.java)): `NONE`, `BASIC`, `STANDARD`, `ELEVATED`, `ADMIN` (ranked 0–4). You only define rules for the levels a brand actually offers.

**Token types** ([`TokenType`](src/main/java/com/yourco/ivr/domain/TokenType.java)) — all seven already have validators, so any brand can use them with no code:

| Token type | Built-in validation rule |
|---|---|
| `ACCOUNT_NUMBER` | non-blank |
| `PIN` | length ≥ 4 |
| `OTP` | exactly 6 digits |
| `SSN_LAST4` | exactly 4 digits |
| `CARD_LAST4` | length == 4 |
| `DATE_OF_BIRTH` | ISO date `YYYY-MM-DD` |
| `VOICE_PRINT` | non-blank |

> These are **stub** validators (see [`validator/impl/`](src/main/java/com/yourco/ivr/validator/impl/)). They check format only, not correctness against a real datastore. Good enough for development and for wiring up flows; production correctness comes from the integration step in §6.

---

## 2. Three ways to create a brand

All three produce the same `config/brands/{brandId}.json` file.

### A. Admin UI (easiest)

1. Start the app (`mvn spring-boot:run`) and open `http://localhost:8081/`.
2. Go to **Brands → New Brand**.
3. Fill in the tabs in [`BrandEditor.tsx`](src/main/ui/src/pages/BrandEditor.tsx):
   - **Rules** — pick levels, add token paths, choose required + backup tokens, set retries/lockout.
   - **Disambiguation** — set max rounds and filter rules (optional).
   - **Flow** — read-only visualization of the resulting paths.
   - **JSON** — read-only preview of the exact file that will be saved.
4. **Save** → issues `POST /api/brands`, validates, writes the file, and registers the brand live (no restart).

### B. REST API

Endpoints from [`BrandController.java`](src/main/java/com/yourco/ivr/api/BrandController.java):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/brands` | List all brands |
| `GET` | `/api/brands/{brandId}` | Get one brand |
| `POST` | `/api/brands` | Create (validates, writes file, registers) |
| `PUT` | `/api/brands/{brandId}` | Update (brandId taken from the path) |
| `DELETE` | `/api/brands/{brandId}` | Delete file + deregister |
| `POST` | `/api/brands/validate` | **Dry-run validate** without saving |

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

### C. Drop a file

Place `config/brands/{brandId}.json` directly in the directory and restart the app. Brand files are loaded at startup (`@PostConstruct` in [`BrandRulesLoader`](src/main/java/com/yourco/ivr/registry/BrandRulesLoader.java) / [`BrandService.loadFromDirectory()`](src/main/java/com/yourco/ivr/service/BrandService.java:149)). A file that fails to parse is logged and skipped, not fatal.

> **Filename note:** the engine derives the filename from `brandId` by lowercasing it and replacing any character outside `[a-zA-Z0-9_-]` with `_` (see [`BrandService.getBrandFile()`](src/main/java/com/yourco/ivr/service/BrandService.java:168)). So `brandId: "BRAND_C"` → `brand_c.json`.

---

## 3. Annotated template

Copy this, rename `brandId`, and trim to the levels you need. (JSON has no comments — strip the `// …` notes before saving; the inline notes are for reading only.)

```jsonc
{
  "brandId": "BRAND_C",                       // unique id; filename becomes brand_c.json

  "disambiguation": {                          // OPTIONAL — omit entirely if one ANI = one party
    "maxDisambiguationTokens": 3,              // max differentiating-token rounds before giving up
    "rules": [
      { "type": "EXCLUDE_INACTIVE" },          // drop parties where active == false
      { "type": "PREFER_PRIMARY_ANI" }         // keep parties flagged primaryAni == true
    ]
  },

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

---

## 4. What validation enforces

`POST`/`PUT` and the `/validate` endpoint run [`BrandService.validate()`](src/main/java/com/yourco/ivr/service/BrandService.java:120). A config is rejected (HTTP 400) unless **all** of these hold:

1. `brandId` is present and non-blank.
2. `levelRules` has at least one level.
3. Every level has at least one `TokenPath`.
4. Every path has at least one entry in `requiredTokens`.

That's the full ruleset — everything else (retries, lockout, backups, disambiguation) is optional and defaulted. Use `POST /api/brands/validate` to check a draft without writing it.

---

## 5. Onboarding checklist

1. **Gather requirements** — which auth levels does this brand offer, and which tokens prove each level?
2. **Confirm token coverage** — every token you reference must be one of the seven in §1. If you need a new one, see §6.
3. **Draft the config** — start from the template in §3 or copy an existing file in `config/brands/`.
4. **Validate** — `POST /api/brands/validate` (or rely on the UI, which validates on save).
5. **Create** — UI **New Brand**, `POST /api/brands`, or drop-file + restart.
6. **Smoke test** — run a full session against the brand (see §7).
7. **Commit the file** — `config/brands/{brandId}.json` is checked into the repo; commit it so the brand exists in every environment.

---

## 6. When code *is* required (framework-level, written once)

These are not per-brand tasks — they're one-time integrations that every brand then benefits from.

| Need | Today | What to add |
|---|---|---|
| **Verify a token against a real backend** (e.g. SSN matches system of record) | A configurable `stub-verify` service ships; real backends are added once | Implement [`TokenLookupService`](src/main/java/com/yourco/ivr/lookup/TokenLookupService.java) as a new `@Component`. It's then **selectable per token, per brand, from config/UI** with no further code (see the brand config's `verificationSources` and the Verification tab). |
| **Look up real customers by ANI** | [`StubPartyLookupProvider`](src/main/java/com/yourco/ivr/partylookup/StubPartyLookupProvider.java) returns a single fake party (`STUB-{ani}`) | Implement [`PartyLookupProvider`](src/main/java/com/yourco/ivr/partylookup/PartyLookupProvider.java) against your CRM/account API and register it as the `@Component`. |
| **Real customer preferences** (blocked tokens, max level) | [`StubCustomerPreferenceProvider`](src/main/java/com/yourco/ivr/preference/StubCustomerPreferenceProvider.java) returns empty | Implement [`CustomerPreferenceProvider`](src/main/java/com/yourco/ivr/preference/CustomerPreferenceProvider.java). |
| **A brand-new token type** | Only the seven in §1 exist | Add the value to [`TokenType`](src/main/java/com/yourco/ivr/domain/TokenType.java) and a matching [`TokenValidator`](src/main/java/com/yourco/ivr/validator/TokenValidator.java) `@Component` in `validator/impl/`. |
| **Different validation for an existing token, one brand only** | Defaults apply to all brands | Register a [`BrandTokenValidatorOverride`](src/main/java/com/yourco/ivr/validator/BrandTokenValidatorOverride.java) bean binding your validator to that `brandId`. |
| **Per-source call-transfer rules** | No UI for this | Edit [`config/transfers/transfer-policies.json`](config/transfers/transfer-policies.json) and restart (transfer policies load at startup only). |

> Per project rule, **any new validator or engine behavior needs a unit/integration test** under `src/test/java/com/yourco/ivr/`.

---

## 7. Verify it works (smoke test)

With the brand created and the server running on `:8081`, drive a full session through the unified endpoint `POST /ivr/authenticate` ([`AuthenticateController`](src/main/java/com/yourco/ivr/api/AuthenticateController.java)). Test values just need to satisfy the format rules in §1 (the stub party lookup accepts any caller).

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

You can also run this end-to-end from the **Dashboard** test console in the UI (start session → submit tokens → watch it reach `AUTHENTICATED`), or explore the API interactively at `http://localhost:8081/swagger-ui.html`.

---

## Related docs

- [README.md](README.md) — quick start, full config reference, API overview.
- [IVR_Auth_Engine_Technical_Spec.md](IVR_Auth_Engine_Technical_Spec.md) — full system design (keep in sync with engine changes).
- [MAINTENANCE_GUIDE.md](MAINTENANCE_GUIDE.md) — operational maintenance notes.

> Per project rule #4, update `README.md` and the Technical Spec whenever brand config structure or engine behavior changes.
