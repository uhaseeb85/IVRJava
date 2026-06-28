// Thin fetch wrappers for the REST API, shared by all UI pages.

export interface BrandSummary {
  brandId: string
  levelRules?: Record<string, unknown>
  identificationOnly?: boolean
}

export interface TransferPolicy {
  sourceSystemId: string
  honoredTokens: string[]
  maxHonoredLevel: string
  enabled: boolean
}

export interface LookupService {
  id: string
  supportedTokens: string[]
  status: string
}

export interface LookupBindings {
  bindings: Record<string, { serviceId: string; failClosed: boolean; params?: Record<string, string> }>
  totalBound: number
}

// ── Brands ───────────────────────────────────────────────────────────────────

export async function getBrands(): Promise<BrandSummary[]> {
  const res = await fetch('/api/brands')
  if (!res.ok) throw new Error(`Failed to load brands (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export function getBrand<T>(id: string): Promise<T> {
  return fetch('/api/brands/' + encodeURIComponent(id)).then(r => {
    if (!r.ok) throw new Error(`Failed to load brand (${r.status})`)
    return r.json()
  })
}

// `_existing` is a frontend-only marker controlling POST (create) vs PUT (update);
// the backend ignores it.
export function saveBrand<T extends { brandId: string; _existing?: boolean }>(cfg: T): Promise<unknown> {
  const isNew = !cfg._existing
  return fetch(isNew ? '/api/brands' : '/api/brands/' + cfg.brandId, {
    method: isNew ? 'POST' : 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg),
  }).then(r => { if (!r.ok) throw new Error('Save failed'); return r.json() })
}

export function deleteBrand(id: string): Promise<Response> {
  return fetch('/api/brands/' + id, { method: 'DELETE' }).then(r => {
    if (!r.ok) throw new Error(`Failed to delete brand (${r.status})`)
    return r
  })
}

/** Clone a brand with a new ID. */
export async function cloneBrand(sourceId: string, newBrandId: string): Promise<unknown> {
  const res = await fetch('/api/brands/' + sourceId + '/clone', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newBrandId }),
  })
  if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.message || 'Clone failed') }
  return res.json()
}

/** Export a brand config as a downloadable JSON blob. */
export async function exportBrandJson(id: string): Promise<Blob> {
  const res = await fetch('/api/brands/' + id + '/export')
  if (!res.ok) throw new Error('Export failed')
  return res.blob()
}

/** Import a brand config from a JSON string. */
export async function importBrandFromJson(jsonContent: string): Promise<unknown> {
  const res = await fetch('/api/brands/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonContent }),
  })
  if (!res.ok) { const err = await res.json().catch(() => ({})); throw new Error(err.message || 'Import failed') }
  return res.json()
}

// ── Transfer Policies ────────────────────────────────────────────────────────

export async function getTransferPolicies(): Promise<TransferPolicy[]> {
  const res = await fetch('/api/transfers')
  if (!res.ok) throw new Error(`Failed to load transfer policies (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export async function saveTransferPolicies(policies: TransferPolicy[]): Promise<TransferPolicy[]> {
  const res = await fetch('/api/transfers', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(policies),
  })
  if (!res.ok) throw new Error('Failed to save transfer policies')
  return res.json()
}

// ── Admin: Sessions ──────────────────────────────────────────────────────────

export interface SessionSummary {
  sessionId: string
  brandId: string
  callerId: string
  currentLevel: string
  targetLevel: string
  status: string
  phase: string
  validatedTokens?: string[] | null
  matchedParty?: Record<string, unknown> | null
  transferredFrom?: string | null
  lockedUntil?: string | null
  createdAt: string
  lastActivityAt: string
}

export async function getActiveSessions(): Promise<SessionSummary[]> {
  const res = await fetch('/api/admin/sessions')
  if (!res.ok) throw new Error(`Failed to load sessions (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export async function searchSessions(params: { brandId?: string; status?: string; callerId?: string }): Promise<SessionSummary[]> {
  const qs = new URLSearchParams()
  if (params.brandId) qs.set('brandId', params.brandId)
  if (params.status) qs.set('status', params.status)
  if (params.callerId) qs.set('callerId', params.callerId)
  const res = await fetch('/api/admin/sessions/search?' + qs.toString())
  if (!res.ok) throw new Error(`Failed to search sessions (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export async function deleteSession(sessionId: string): Promise<void> {
  const res = await fetch('/api/admin/sessions/' + sessionId, { method: 'DELETE' })
  if (!res.ok) throw new Error('Failed to delete session')
}

// ── Admin: Lookup Services ───────────────────────────────────────────────────

export async function getLookupServices(): Promise<LookupService[]> {
  const res = await fetch('/api/lookup-services')
  if (!res.ok) throw new Error(`Failed to load lookup services (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export async function getLookupBindings(): Promise<LookupBindings> {
  const res = await fetch('/api/lookup-services/bindings')
  if (!res.ok) throw new Error(`Failed to load bindings (${res.status})`)
  return res.json()
}
