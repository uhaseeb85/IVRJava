// Thin fetch wrappers for the brand-config REST API, shared by the Dashboard,
// Brands, and Brand Editor pages.

export interface BrandSummary {
  brandId: string
  levelRules?: Record<string, unknown>
  identificationOnly?: boolean
}

export async function getBrands(): Promise<BrandSummary[]> {
  const res = await fetch('/api/brands')
  if (!res.ok) throw new Error(`Failed to load brands (${res.status})`)
  const data = await res.json()
  return Array.isArray(data) ? data : []
}

export function getBrand<T>(id: string): Promise<T> {
  return fetch('/api/brands/' + id).then(r => r.json())
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
  return fetch('/api/brands/' + id, { method: 'DELETE' })
}
