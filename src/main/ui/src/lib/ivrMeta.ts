// Shared IVR metadata for the admin console and guided Test Console.
// Pure constants — no logic. Mirrors the small, stable backend enums plus
// human-friendly labels so a first-time user never needs to know the raw values.

export const LEVELS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const

// Full progression order including the implicit starting point.
export const LEVEL_ORDER = ['NONE', 'BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const

export const TOKENS = [
  'ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4',
] as const

// Session statuses shown in the Active Sessions admin page (mirrors the SessionStatus enum).
export const STATUSES = ['COLLECTING', 'AUTHENTICATED', 'FAILED', 'REDIRECT_TO_AGENT', 'EXPIRED'] as const

// Sample values that all pass the (lenient stub) validators, so the user can
// complete a flow without knowing what a "valid" token looks like.
export const TOKEN_DEFAULTS: Record<string, string> = {
  ACCOUNT_NUMBER: '123456789',
  PIN: '1234',
  OTP: '123456',
  SSN_LAST4: '1234',
  DATE_OF_BIRTH: '1985-03-15',
  CARD_LAST4: '4242',
  VOICE_PRINT: 'voiceprint-sample',
}

// Plain-language description of what each access level lets a caller do.
export const LEVEL_BLURB: Record<string, string> = {
  NONE: 'Not yet verified',
  BASIC: 'Check balance & basic account info',
  STANDARD: 'Make payments & routine account changes',
  ELEVATED: 'Transfers & sensitive changes',
  ADMIN: 'Full account control',
}

// Human-readable name for each token, used in spoken-style prompts and labels.
const TOKEN_LABEL: Record<string, string> = {
  ACCOUNT_NUMBER: 'account number',
  PIN: 'PIN',
  OTP: 'one-time passcode',
  SSN_LAST4: 'last 4 digits of your SSN',
  VOICE_PRINT: 'voice print',
  DATE_OF_BIRTH: 'date of birth',
  CARD_LAST4: 'last 4 digits of your card',
}

// A few sample caller numbers. The stub party lookup accepts ANY number, so
// these are just convenient starting points.
export const SAMPLE_CALLERS: { ani: string; label: string }[] = [
  { ani: '5551234567', label: 'Standard caller' },
  { ani: '5557654321', label: 'Backup-token caller' },
  { ani: '5551112222', label: 'Fallback-path caller' },
]

export function tokenLabel(t?: string | null): string {
  if (!t) return 'token'
  return TOKEN_LABEL[t] ?? t.toLowerCase().replace(/_/g, ' ')
}

// Levels a brand supports, in canonical order. Falls back to all levels if unknown.
export function levelsForBrand(brand?: { levelRules?: Record<string, unknown> }): string[] {
  const keys = brand?.levelRules ? Object.keys(brand.levelRules) : []
  const supported = keys.length > 0 ? keys.map(k => k.toUpperCase()) : [...LEVELS]
  return LEVEL_ORDER.filter(l => l !== 'NONE' && supported.includes(l))
}
