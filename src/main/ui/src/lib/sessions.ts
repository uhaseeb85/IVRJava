export interface SessionStep {
  at: string
  type: 'start' | 'token' | 'escalate'
  label: string
  response: Record<string, unknown>
  status: string
}

export interface SessionRecord {
  id: string
  brandId: string
  callerId: string
  targetLevel: string
  startedAt: string
  finalStatus: string
  steps: SessionStep[]
}

const KEY = 'ivr_sessions_v1'

export function loadSessions(): SessionRecord[] {
  try { return JSON.parse(localStorage.getItem(KEY) ?? '[]') } catch { return [] }
}

export function upsertSession(s: SessionRecord): void {
  const all = loadSessions()
  const i = all.findIndex(x => x.id === s.id)
  if (i >= 0) all[i] = s; else all.unshift(s)
  localStorage.setItem(KEY, JSON.stringify(all.slice(0, 200)))
}

export function clearSessions(): void {
  localStorage.removeItem(KEY)
}
