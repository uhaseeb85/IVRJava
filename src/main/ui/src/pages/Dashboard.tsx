import { useState, useEffect, useCallback, useRef } from 'react'
import { cn } from '../lib/utils'
import {
  Send, Loader2, XCircle, Copy, Check, RotateCcw, ChevronDown, ChevronUp, Clock,
  Eye, EyeOff, Layers, Activity, Phone, PhoneCall, Volume2, Lock, CheckCircle2,
  ShieldCheck, Wrench, ArrowUpRight,
} from 'lucide-react'
import { type SessionRecord, type SessionStep, upsertSession, loadSessions } from '../lib/sessions'
import {
  LEVELS, LEVEL_ORDER, TOKEN_DEFAULTS, LEVEL_BLURB, SAMPLE_CALLERS, tokenLabel,
} from '../lib/ivrMeta'

interface BrandSummary { brandId: string; levelRules?: Record<string, unknown>; identificationOnly?: boolean }

function statusStyle(status: string) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return 'text-emerald-700 bg-emerald-50 border-emerald-200'
  if (s === 'FAILED') return 'text-red-600 bg-red-50 border-red-200'
  if (s === 'REDIRECT_TO_AGENT') return 'text-orange-600 bg-orange-50 border-orange-200'
  if (s === 'COLLECTING') return 'text-amber-700 bg-amber-50 border-amber-200'
  return 'text-slate-500 bg-slate-100 border-slate-200'
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-bold tracking-wide', statusStyle(status))}>
      {status.toUpperCase()}
    </span>
  )
}

function msToText(ms: number) {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// Levels a brand supports, in canonical order. Falls back to all levels if unknown.
function levelsForBrand(brand?: BrandSummary): string[] {
  const keys = brand?.levelRules ? Object.keys(brand.levelRules) : []
  const supported = keys.length > 0 ? keys.map(k => k.toUpperCase()) : [...LEVELS]
  return LEVEL_ORDER.filter(l => l !== 'NONE' && supported.includes(l))
}

// ── Progress ladder: NONE → BASIC → … → ADMIN ──
function LevelLadder({ current, target }: { current?: string; target?: string }) {
  const cur = (current || 'NONE').toUpperCase()
  const tgt = (target || '').toUpperCase()
  const curIdx = LEVEL_ORDER.indexOf(cur as typeof LEVEL_ORDER[number])
  const tgtIdx = LEVEL_ORDER.indexOf(tgt as typeof LEVEL_ORDER[number])
  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      {LEVEL_ORDER.map((lvl, i) => {
        const reached = curIdx >= 0 && i <= curIdx
        const isTarget = i === tgtIdx
        return (
          <div key={lvl} className="flex items-center gap-1.5">
            {i > 0 && (
              <span className={cn('h-px w-4', reached ? 'bg-emerald-300' : 'bg-slate-200')} />
            )}
            <span className={cn(
              'inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-bold transition-colors',
              reached
                ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
                : isTarget
                  ? 'bg-indigo-50 border-indigo-200 text-indigo-700 ring-1 ring-indigo-200'
                  : 'bg-slate-50 border-slate-200 text-slate-400'
            )}>
              {reached && <Check size={11} />}
              {lvl}
              {isTarget && !reached && <span className="text-[9px] font-semibold opacity-70">goal</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

// Visual tone for a timeline step, derived from its status (+ soft-fail flag).
// `icon` is null for in-progress steps so the caller can fall back to the step number.
function stepTone(status: string, failed?: boolean): { row: string; badge: string; icon: string | null } {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return { row: 'bg-emerald-50 border-emerald-100', badge: 'bg-emerald-200 text-emerald-800', icon: '✓' }
  if (s === 'FAILED' || s === 'REDIRECT_TO_AGENT') return { row: 'bg-red-50 border-red-100', badge: 'bg-red-200 text-red-700', icon: '✗' }
  if (failed) return { row: 'bg-amber-50 border-amber-100', badge: 'bg-amber-200 text-amber-800', icon: '✗' }
  return { row: 'bg-slate-50 border-slate-100', badge: 'bg-slate-200 text-slate-600', icon: null }
}

// Badge + text colors for a processing-log entry, keyed by its level.
function procLogTone(level: string): { badge: string; text: string } {
  const l = level.toUpperCase()
  if (l === 'PASS') return { badge: 'bg-emerald-100 text-emerald-700', text: 'text-emerald-800' }
  if (l === 'FAIL') return { badge: 'bg-red-100 text-red-600',     text: 'text-red-700' }
  if (l === 'WARN') return { badge: 'bg-amber-100 text-amber-700', text: 'text-amber-800' }
  return              { badge: 'bg-slate-100 text-slate-500',  text: 'text-slate-600' }
}

// Collapsible per-request processing log emitted by the auth engine.
function ProcessingLog({ log, open, onToggle }: {
  log: Array<{ level: string; message: string }>
  open: boolean
  onToggle: () => void
}) {
  return (
    <div className="rounded-lg border border-slate-200 overflow-hidden">
      <button
        onClick={onToggle}
        className="w-full px-4 py-2.5 flex items-center justify-between hover:bg-slate-50 transition-colors"
      >
        <div className="flex items-center gap-2 text-xs font-bold text-slate-700">
          <Activity size={12} className="text-indigo-500" />
          Processing Log
          <span className="text-[10px] font-normal text-slate-400 ml-1">
            ({log.length} event{log.length !== 1 ? 's' : ''})
          </span>
        </div>
        {open ? <ChevronUp size={13} className="text-slate-400" /> : <ChevronDown size={13} className="text-slate-400" />}
      </button>
      {open && (
        <div className="border-t border-slate-100 divide-y divide-slate-50">
          {log.map((entry, i) => {
            const tone = procLogTone(entry.level)
            return (
              <div key={i} className="flex items-start gap-3 px-4 py-2.5">
                <span className={cn('shrink-0 rounded px-1.5 py-0.5 text-[10px] font-black w-9 text-center mt-0.5', tone.badge)}>
                  {entry.level.toUpperCase()}
                </span>
                <span className={cn('text-xs leading-relaxed font-mono', tone.text)}>
                  {entry.message}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

export default function Dashboard() {
  const [session, setSession] = useState<SessionRecord | null>(null)
  const [response, setResponse] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState('')
  const [errorBody, setErrorBody] = useState('')
  const [form, setForm] = useState<Record<string, string>>({ callerId: SAMPLE_CALLERS[0].ani })
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [copiedId, setCopiedId] = useState(false)
  const [showRaw, setShowRaw] = useState(false)
  const [showRequest, setShowRequest] = useState(false)
  const [showDev, setShowDev] = useState(false)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [lastRequest, setLastRequest] = useState<Record<string, unknown> | null>(null)
  const [lastTiming, setLastTiming] = useState<number | null>(null)
  // Ref so steps always capture their own timing (useState update is async/stale in closures)
  const timingRef = useRef<number>(0)
  const [stats, setStats] = useState({ today: 0, total: 0, brands: [] as BrandSummary[] })
  const [recentSessions, setRecentSessions] = useState<SessionRecord[]>([])
  const [showRecent, setShowRecent] = useState(false)
  const [showProcLog, setShowProcLog] = useState(true)

  useEffect(() => {
    const all = loadSessions()
    const today = all.filter(s => new Date(s.startedAt).toDateString() === new Date().toDateString()).length
    setRecentSessions(all.slice(0, 5))
    setStats(s => ({ ...s, today, total: all.length }))
    fetch('/api/brands')
      .then(r => r.json())
      .then(d => setStats(s => ({ ...s, brands: Array.isArray(d) ? d : [] })))
      .catch(() => {})
  }, [])

  const post = useCallback(async (body: Record<string, unknown>): Promise<Record<string, unknown> | null> => {
    setError('')
    setErrorBody('')
    setLoading(true)
    setLastRequest(body)
    setShowRequest(false)
    const start = performance.now()
    try {
      const res = await fetch('/ivr/authenticate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const elapsed = performance.now() - start
      setLastTiming(elapsed)
      timingRef.current = elapsed   // synchronous — available immediately after await post()
      const data = await res.json()
      if (!res.ok) {
        setError(data.message || res.statusText)
        setErrorBody(JSON.stringify(data, null, 2))
        return null
      }
      return data as Record<string, unknown>
    } catch {
      setError('Network error — is the backend running?')
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  const placeCall = async () => {
    if (session) return
    const payload: Record<string, unknown> = {
      brandId: form.brandId,
      callerId: form.callerId || SAMPLE_CALLERS[0].ani,
      targetLevel: form.targetLevel,
    }
    if (form.transfer === 'yes') {
      payload.sourceSystemId = 'LEGACY_IVR'
      payload.currentLevel = 'NONE'
      payload.validatedTokens = ['ACCOUNT_NUMBER']
    }
    const data = await post(payload)
    if (!data) return
    setResponse(data)
    const step: SessionStep = {
      at: new Date().toISOString(),
      type: 'start',
      label: `Call placed → aiming for ${payload.targetLevel}${form.transfer === 'yes' ? ' (transfer)' : ''}`,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
      timing: timingRef.current || undefined,
    }
    const rec: SessionRecord = {
      id: String(data.sessionId ?? `local-${Date.now()}`),
      brandId: String(payload.brandId),
      callerId: String(payload.callerId),
      targetLevel: String(payload.targetLevel),
      startedAt: new Date().toISOString(),
      finalStatus: String(data.status ?? 'COLLECTING'),
      steps: [step],
    }
    setSession(rec)
    upsertSession(rec)
    setRecentSessions(loadSessions().slice(0, 5))
    setStats(s => ({ ...s, today: s.today + 1, total: s.total + 1 }))
  }

  const appendStep = (step: SessionStep, data: Record<string, unknown>) => {
    if (!session) return
    const updated: SessionRecord = {
      ...session,
      steps: [...session.steps, step],
      finalStatus: String(data.status ?? session.finalStatus),
    }
    setSession(updated)
    upsertSession(updated)
    setRecentSessions(loadSessions().slice(0, 5))
  }

  const submitToken = async () => {
    if (!session) return
    const payload = {
      sessionId: session.id,
      tokenType: form.tokenType || 'PIN',
      tokenValue: form.tokenValue || '',
    }

    // Snapshot the current nextRequiredToken *before* the request so we can tell
    // whether the server kept asking for the same token (= rejection) or advanced.
    const prevStep = session.steps[session.steps.length - 1]
    const prevRequired = prevStep?.response?.nextRequiredToken as string | undefined

    const data = await post(payload)
    if (!data) return
    setResponse(data)
    if (data.processingLog) setShowProcLog(true)   // auto-expand log for every token response

    const currRequired = data.nextRequiredToken as string | undefined
    const remaining = data.remainingAttempts as number | undefined

    // Rejection: the server is still asking for the same required token
    const wasFailure = data.status === 'COLLECTING'
      && currRequired != null
      && currRequired === prevRequired

    const label = wasFailure
      ? `${tokenLabel(payload.tokenType)} not accepted${remaining != null
          ? ` — ${remaining} ${remaining === 1 ? 'try' : 'tries'} left`
          : ''}`
      : `Provided ${tokenLabel(payload.tokenType)}`

    appendStep({
      at: new Date().toISOString(),
      type: 'token',
      label,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
      timing: timingRef.current || undefined,
      failed: wasFailure || undefined,
      remainingAttempts: wasFailure ? remaining : undefined,
    }, data)
  }

  const escalate = async (lvlArg?: string) => {
    if (!session) return
    const level = lvlArg || form.escalateLevel || 'ELEVATED'
    const data = await post({ sessionId: session.id, targetLevel: level })
    if (!data) return
    setResponse(data)
    setSession(s => (s ? { ...s, targetLevel: level } : s))
    appendStep({
      at: new Date().toISOString(),
      type: 'escalate',
      label: `Asked for more access → ${level}`,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
      timing: timingRef.current || undefined,
    }, data)
  }

  const reset = () => {
    setSession(null)
    setResponse(null)
    setError('')
    setErrorBody('')
    setLastRequest(null)
    setLastTiming(null)
    setForm(f => ({ brandId: f.brandId, targetLevel: f.targetLevel, callerId: f.callerId || SAMPLE_CALLERS[0].ani }))
  }

  const restoreSession = (rec: SessionRecord) => {
    reset()
    setSession(rec)
    if (rec.steps.length > 0) {
      setResponse(rec.steps[rec.steps.length - 1].response)
    }
  }

  const copyJSON = () => {
    navigator.clipboard.writeText(JSON.stringify(response, null, 2))
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const copyId = () => {
    if (!session) return
    navigator.clipboard.writeText(session.id)
    setCopiedId(true)
    setTimeout(() => setCopiedId(false), 2000)
  }

  const currentStatus = session?.finalStatus?.toUpperCase() ?? 'IDLE'
  const isDone = ['AUTHENTICATED', 'FAILED', 'REDIRECT_TO_AGENT'].includes(currentStatus)
  const sessionActive = !!session && !isDone

  // Drive the token input from the server: auto-select the token it's asking for
  // and pre-fill a valid sample value so the caller never has to guess.
  const nextRequired = response?.nextRequiredToken as string | undefined
  useEffect(() => {
    if (sessionActive && nextRequired) {
      setForm(f => ({ ...f, tokenType: nextRequired, tokenValue: TOKEN_DEFAULTS[nextRequired] ?? '' }))
    }
  }, [nextRequired, sessionActive])

  // Ctrl+Enter shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && sessionActive) {
        e.preventDefault()
        submitToken()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  })

  const inputCls = 'w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition disabled:opacity-50 disabled:cursor-not-allowed'

  const selectedBrand = stats.brands.find(b => b.brandId === form.brandId)
  const brandLevels = levelsForBrand(selectedBrand)
  const idOnly = !!selectedBrand?.identificationOnly
  const canPlaceCall = !!form.brandId && !!form.targetLevel && !loading

  const acceptedTokens = (response?.acceptedTokens as string[] | undefined) ?? []
  const activeToken = form.tokenType || nextRequired || 'PIN'
  const alternatives = acceptedTokens.filter(t => t !== activeToken)

  const currentLevel = String(response?.currentLevel ?? 'NONE')
  const higherLevels = brandLevels.filter(l => LEVEL_ORDER.indexOf(l as typeof LEVEL_ORDER[number]) > LEVEL_ORDER.indexOf(currentLevel.toUpperCase() as typeof LEVEL_ORDER[number]))
  // Identification-only result: the caller was resolved to a single party, no auth level granted.
  const identified = currentStatus === 'AUTHENTICATED' && (idOnly || currentLevel.toUpperCase() === 'NONE')

  const procLog = response?.processingLog as Array<{ level: string; message: string }> | undefined

  const responseSummary = response ? (
    <div className="space-y-1.5">
      {['status', 'currentLevel', 'targetLevel', 'nextRequiredToken', 'prompt', 'phase', 'matchedPartyId', 'remainingAttempts', 'acceptedTokens'].map(k => {
        const v = response[k]
        if (v == null || v === '' || (Array.isArray(v) && v.length === 0)) return null
        return (
          <div key={k} className="flex items-baseline gap-2 text-xs">
            <span className="text-slate-400 font-mono shrink-0 w-36">{k}</span>
            <span className="text-slate-300 shrink-0">→</span>
            {Array.isArray(v) ? (
              <div className="flex flex-wrap gap-1">
                {(v as string[]).map(t => (
                  <span key={t} className="bg-slate-200 text-slate-600 rounded px-1.5 py-0.5 text-[10px] font-mono">{t}</span>
                ))}
              </div>
            ) : (
              <span className="text-slate-800 font-mono">{String(v)}</span>
            )}
          </div>
        )
      })}
    </div>
  ) : null

  return (
    <div className="flex flex-col min-h-full">
      {/* Page header */}
      <div className="bg-white border-b border-slate-200 px-8 py-4 flex items-center gap-6 shrink-0">
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-bold text-slate-900">Test Console</h1>
          <p className="text-sm text-slate-500 mt-0.5">Simulate a caller phoning in and getting verified — step by step</p>
        </div>

        {/* Stats chips */}
        <div className="hidden md:flex items-center gap-2">
          <span className="bg-slate-100 text-slate-600 rounded-full px-2.5 py-1 text-xs font-semibold">
            {stats.brands.length} brand{stats.brands.length !== 1 ? 's' : ''}
          </span>
          <span className="bg-slate-100 text-slate-600 rounded-full px-2.5 py-1 text-xs font-semibold">
            {stats.today} today
          </span>
          <span className="bg-slate-100 text-slate-600 rounded-full px-2.5 py-1 text-xs font-semibold">
            {stats.total} total
          </span>
        </div>

        {/* Active session pill */}
        {session && (
          <div className="flex items-center gap-3 pl-4 border-l border-slate-200">
            <div className="flex items-center gap-2 bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5">
              <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider">Call</span>
              <code className="text-xs font-mono text-indigo-600">{session.id.slice(0, 10)}…</code>
              <button onClick={copyId} className="text-slate-400 hover:text-indigo-600 transition-colors">
                {copiedId ? <Check size={11} className="text-emerald-500" /> : <Copy size={11} />}
              </button>
            </div>
            <StatusBadge status={currentStatus} />
            <button
              onClick={reset}
              className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-900 transition-colors font-medium"
            >
              <RotateCcw size={12} />New call
            </button>
          </div>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-3xl mx-auto space-y-5">

          {/* ─────────────── STAGE 1 — Who's calling? ─────────────── */}
          {!session && (
            <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-6">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-indigo-50 flex items-center justify-center">
                  <Phone size={18} className="text-indigo-600" />
                </div>
                <div>
                  <h2 className="text-base font-bold text-slate-900">Start a call</h2>
                  <p className="text-sm text-slate-500">Pick who's calling and what they want to do.</p>
                </div>
              </div>

              {/* Brand picker */}
              <div>
                <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">1 · Which brand are they calling?</p>
                {stats.brands.length === 0 ? (
                  <p className="text-sm text-slate-400 italic">No brands configured yet — add one on the Brands page first.</p>
                ) : (
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
                    {stats.brands.map(b => {
                      const levels = levelsForBrand(b)
                      const selected = form.brandId === b.brandId
                      return (
                        <button
                          key={b.brandId}
                          onClick={() => setForm(f => ({ ...f, brandId: b.brandId, targetLevel: b.identificationOnly ? 'NONE' : '' }))}
                          className={cn(
                            'text-left rounded-xl border p-3 transition-all',
                            selected
                              ? 'border-indigo-400 bg-indigo-50/60 ring-1 ring-indigo-200'
                              : 'border-slate-200 bg-white hover:border-indigo-200 hover:bg-slate-50'
                          )}
                        >
                          <div className="flex items-center gap-1.5">
                            <ShieldCheck size={14} className={selected ? 'text-indigo-600' : 'text-slate-400'} />
                            <span className="text-sm font-bold text-slate-800 truncate">{b.brandId}</span>
                          </div>
                          <div className="flex flex-wrap gap-1 mt-2">
                            {b.identificationOnly
                              ? <span className="bg-sky-100 text-sky-600 rounded px-1.5 py-0.5 text-[10px] font-semibold">ID ONLY</span>
                              : levels.map(l => (
                                  <span key={l} className="bg-slate-100 text-slate-500 rounded px-1.5 py-0.5 text-[10px] font-semibold">{l}</span>
                                ))}
                          </div>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>

              {/* Identification-only note (replaces the level chooser) */}
              {form.brandId && idOnly && (
                <div className="rounded-xl border border-sky-200 bg-sky-50/60 p-3">
                  <p className="text-xs font-bold text-sky-700 uppercase tracking-wider mb-1">2 · Identification only</p>
                  <p className="text-sm text-sky-700">
                    This brand just identifies the caller — no auth level is granted. The call ends once a single party is resolved (access level stays NONE).
                  </p>
                </div>
              )}

              {/* Level chooser */}
              {form.brandId && !idOnly && (
                <div>
                  <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">2 · What do they need to do?</p>
                  <div className="space-y-2">
                    {brandLevels.map(l => {
                      const selected = form.targetLevel === l
                      return (
                        <button
                          key={l}
                          onClick={() => setForm(f => ({ ...f, targetLevel: l }))}
                          className={cn(
                            'w-full text-left rounded-xl border p-3 flex items-center gap-3 transition-all',
                            selected
                              ? 'border-indigo-400 bg-indigo-50/60 ring-1 ring-indigo-200'
                              : 'border-slate-200 bg-white hover:border-indigo-200 hover:bg-slate-50'
                          )}
                        >
                          <span className={cn(
                            'w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0',
                            selected ? 'border-indigo-500 bg-indigo-500' : 'border-slate-300'
                          )}>
                            {selected && <Check size={11} className="text-white" />}
                          </span>
                          <span className="shrink-0 text-sm font-bold text-slate-800 w-24">{l}</span>
                          <span className="text-sm text-slate-500">{LEVEL_BLURB[l]}</span>
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}

              {/* Caller */}
              {form.targetLevel && (
                <div>
                  <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">3 · Calling from</p>
                  <input
                    className={inputCls}
                    placeholder={SAMPLE_CALLERS[0].ani}
                    value={form.callerId || ''}
                    onChange={e => setForm({ ...form, callerId: e.target.value })}
                  />
                  <div className="flex flex-wrap items-center gap-1.5 mt-2">
                    <span className="text-[11px] text-slate-400">any number works in test mode —</span>
                    {SAMPLE_CALLERS.map(c => (
                      <button
                        key={c.ani}
                        onClick={() => setForm(f => ({ ...f, callerId: c.ani }))}
                        className="rounded-full border border-slate-200 bg-white px-2.5 py-0.5 text-[11px] font-medium text-slate-500 hover:border-indigo-300 hover:text-indigo-600 transition-colors"
                      >
                        {c.ani}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Advanced: transfer */}
              {form.targetLevel && (
                <div className="border-t border-slate-100 pt-3">
                  <button
                    onClick={() => setShowAdvanced(v => !v)}
                    className="flex items-center gap-1.5 text-xs font-semibold text-slate-500 hover:text-slate-800 transition-colors"
                  >
                    {showAdvanced ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                    Advanced
                  </button>
                  {showAdvanced && (
                    <label className="flex items-center gap-2 mt-2.5 text-sm text-slate-600 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={form.transfer === 'yes'}
                        onChange={e => setForm(f => ({ ...f, transfer: e.target.checked ? 'yes' : '' }))}
                        className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500/30"
                      />
                      Simulate a transfer from a legacy system (caller already verified their account number)
                    </label>
                  )}
                </div>
              )}

              {/* Place call */}
              <button
                onClick={placeCall}
                disabled={!canPlaceCall}
                className="w-full rounded-xl bg-indigo-600 px-4 py-3 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2 shadow-sm"
              >
                {loading ? <Loader2 size={16} className="animate-spin" /> : <PhoneCall size={16} />}
                Place call
              </button>
            </div>
          )}

          {/* Network / server error (always prominent) */}
          {error && (
            <div className="bg-white rounded-xl border border-red-200 shadow-sm overflow-hidden">
              <button
                onClick={() => errorBody ? setErrorBody('') : setErrorBody('(no details)')}
                className="w-full p-4 flex items-center gap-2 text-sm text-red-600 hover:bg-red-50 transition-colors text-left"
              >
                <XCircle size={15} className="shrink-0" />
                <span className="flex-1 font-medium">{error}</span>
                {errorBody && (errorBody === '(no details)' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
              </button>
              {errorBody && errorBody !== '(no details)' && (
                <pre className="bg-red-50 border-t border-red-100 text-red-800 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
                  {errorBody}
                </pre>
              )}
            </div>
          )}

          {/* ─────────────── STAGE 2 — On the call ─────────────── */}
          {session && (
            <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-5">
              {/* Progress ladder (authentication brands only) */}
              {!idOnly && (
                <div className="flex items-center justify-between gap-4">
                  <LevelLadder current={currentLevel} target={session.targetLevel} />
                </div>
              )}

              {/* Spoken prompt bubble */}
              {response?.prompt ? (
                <div className="flex items-start gap-3 rounded-xl bg-slate-50 border border-slate-100 p-4">
                  <div className="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center shrink-0">
                    <Volume2 size={15} className="text-indigo-600" />
                  </div>
                  <div className="flex-1">
                    <p className="text-[10px] font-bold uppercase tracking-widest text-slate-400 mb-0.5">The system says</p>
                    <p className="text-sm text-slate-800 font-medium leading-relaxed">{String(response.prompt)}</p>
                  </div>
                </div>
              ) : null}

              {/* Active: token entry */}
              {sessionActive && (
                <div className="space-y-3">
                  <div>
                    <label className="block text-xs font-semibold text-slate-500 mb-1">
                      Enter the caller's <span className="text-slate-800">{tokenLabel(activeToken)}</span>
                    </label>
                    <div className="flex gap-2">
                      <input
                        autoFocus
                        className={inputCls}
                        placeholder={TOKEN_DEFAULTS[activeToken] ?? ''}
                        value={form.tokenValue || ''}
                        onChange={e => setForm({ ...form, tokenValue: e.target.value })}
                        onKeyDown={e => e.key === 'Enter' && submitToken()}
                      />
                      <button
                        onClick={submitToken}
                        disabled={loading}
                        className="shrink-0 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2 shadow-sm"
                      >
                        {loading ? <Loader2 size={15} className="animate-spin" /> : <Send size={14} />}
                        Submit
                      </button>
                    </div>
                    <p className="text-[11px] text-slate-400 mt-1.5">
                      Pre-filled with a valid sample value — just press Enter. (Clear it or change it to test a failure.)
                    </p>
                  </div>

                  {/* Backup-token alternatives */}
                  {alternatives.length > 0 && (
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-[11px] text-slate-400">Can't provide that? Use instead:</span>
                      {alternatives.map(alt => (
                        <button
                          key={alt}
                          onClick={() => setForm(f => ({ ...f, tokenType: alt, tokenValue: TOKEN_DEFAULTS[alt] ?? '' }))}
                          className="rounded-full border border-slate-200 bg-white px-2.5 py-0.5 text-[11px] font-medium text-slate-600 hover:border-indigo-300 hover:text-indigo-600 transition-colors"
                        >
                          {tokenLabel(alt)}
                        </button>
                      ))}
                    </div>
                  )}

                  {/* Inline retry warning */}
                  {response?.remainingAttempts != null && (
                    <div className={cn(
                      'rounded-lg border px-3 py-2 text-xs font-semibold',
                      (response.remainingAttempts as number) <= 1
                        ? 'bg-red-50 border-red-200 text-red-600'
                        : 'bg-amber-50 border-amber-200 text-amber-700'
                    )}>
                      {response.remainingAttempts as number} {(response.remainingAttempts as number) === 1 ? 'try' : 'tries'} left for the {tokenLabel(activeToken)}.
                    </div>
                  )}
                </div>
              )}

              {/* Terminal: identified (identification-only brands) */}
              {identified && (
                <div className="rounded-xl bg-sky-50 border border-sky-200 p-4">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 size={18} className="text-sky-600" />
                    <p className="text-sm font-bold text-sky-800">
                      Identified{response?.matchedPartyId ? ` — party ${String(response.matchedPartyId)}` : ''}
                    </p>
                  </div>
                  <p className="text-xs text-sky-700 mt-1">Access level: NONE (identification only).</p>
                </div>
              )}

              {/* Terminal: authenticated */}
              {currentStatus === 'AUTHENTICATED' && !identified && (
                <div className="rounded-xl bg-emerald-50 border border-emerald-200 p-4">
                  <div className="flex items-center gap-2">
                    <CheckCircle2 size={18} className="text-emerald-600" />
                    <p className="text-sm font-bold text-emerald-800">Verified — reached {currentLevel} access</p>
                  </div>
                  {higherLevels.length > 0 && (
                    <div className="mt-3">
                      <p className="text-xs text-emerald-700 mb-1.5">Need to do more? Ask for higher access:</p>
                      <div className="flex flex-wrap gap-1.5">
                        {higherLevels.map(l => (
                          <button
                            key={l}
                            onClick={() => escalate(l)}
                            disabled={loading}
                            className="rounded-full border border-emerald-300 bg-white px-3 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-100 disabled:opacity-50 transition-colors flex items-center gap-1.5"
                          >
                            <ArrowUpRight size={12} />{l}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* Terminal: redirect to agent / failed */}
              {(currentStatus === 'REDIRECT_TO_AGENT' || currentStatus === 'FAILED') && (
                <div className="rounded-xl bg-red-50 border border-red-200 p-4">
                  <div className="flex items-center gap-2">
                    <Lock size={17} className="text-red-600" />
                    <p className="text-sm font-bold text-red-700">
                      {currentStatus === 'REDIRECT_TO_AGENT' ? 'Redirect to Agent' : 'Verification failed'}
                    </p>
                  </div>
                  {typeof response?.lockedUntil === 'string' && (
                    <p className="text-xs text-red-500 mt-1">Redirecting until {new Date(response.lockedUntil).toLocaleTimeString()}</p>
                  )}
                  <button
                    onClick={reset}
                    className="mt-3 rounded-lg bg-red-600 px-3.5 py-1.5 text-xs font-bold text-white hover:bg-red-500 transition-colors flex items-center gap-1.5"
                  >
                    <RotateCcw size={12} />Start a new call
                  </button>
                </div>
              )}
            </div>
          )}

          {/* ─────────────── STAGE 3 — Developer details ─────────────── */}
          {(session || response) && (
            <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
              <button
                onClick={() => setShowDev(v => !v)}
                className="w-full px-5 py-3 flex items-center justify-between hover:bg-slate-50 transition-colors"
              >
                <div className="flex items-center gap-2 text-sm font-bold text-slate-700">
                  <Wrench size={13} className="text-slate-400" />
                  Developer details
                  <span className="text-[10px] font-normal text-slate-400 ml-1">timeline · request · response · log</span>
                </div>
                {showDev ? <ChevronUp size={14} className="text-slate-400" /> : <ChevronDown size={14} className="text-slate-400" />}
              </button>

              {showDev && (
                <div className="border-t border-slate-100 p-5 space-y-4">
                  {/* Auth Flow timeline */}
                  {session && session.steps.length > 0 && (
                    <div>
                      <h3 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Call timeline</h3>
                      <div className="space-y-2">
                        {session.steps.map((step, i) => {
                          const s = step.status.toUpperCase()
                          const tone = stepTone(s, step.failed)
                          return (
                            <div key={i} className={cn(
                              'flex items-center gap-3 rounded-lg px-3 py-2.5 border', tone.row
                            )}>
                              <div className={cn(
                                'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0', tone.badge
                              )}>
                                {tone.icon ?? i + 1}
                              </div>
                              <span className={cn(
                                'flex-1 text-sm font-semibold',
                                step.failed ? 'text-amber-800' : 'text-slate-700'
                              )}>{step.label}</span>
                              {step.failed && step.remainingAttempts != null && (
                                <span className={cn(
                                  'shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-black tabular-nums',
                                  step.remainingAttempts <= 1
                                    ? 'bg-red-100 border-red-200 text-red-700'
                                    : 'bg-amber-100 border-amber-200 text-amber-700'
                                )}>
                                  {step.remainingAttempts} left
                                </span>
                              )}
                              <StatusBadge status={s} />
                              {step.timing != null && (
                                <span className="flex items-center gap-1 text-[10px] text-slate-400 font-mono">
                                  <Clock size={10} />{msToText(step.timing)}
                                </span>
                              )}
                              <span className="text-[10px] text-slate-400 font-mono tabular-nums">
                                {new Date(step.at).toLocaleTimeString()}
                              </span>
                            </div>
                          )
                        })}
                      </div>
                    </div>
                  )}

                  {/* Request preview */}
                  {lastRequest && (
                    <div className="rounded-lg border border-slate-200 overflow-hidden">
                      <button
                        onClick={() => setShowRequest(r => !r)}
                        className="w-full px-4 py-2.5 flex items-center justify-between hover:bg-slate-50 transition-colors"
                      >
                        <div className="flex items-center gap-2 text-xs font-bold text-slate-700">
                          <Eye size={12} />
                          Request sent
                          {lastTiming != null && (
                            <span className="text-[10px] font-normal text-slate-400 ml-1">({msToText(lastTiming)})</span>
                          )}
                        </div>
                        {showRequest ? <ChevronUp size={13} className="text-slate-400" /> : <ChevronDown size={13} className="text-slate-400" />}
                      </button>
                      {showRequest && (
                        <pre className="bg-slate-50 border-t border-slate-100 text-slate-700 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
                          {JSON.stringify(lastRequest, null, 2)}
                        </pre>
                      )}
                    </div>
                  )}

                  {/* Response */}
                  {response && (
                    <div className="rounded-lg border border-slate-200 p-4">
                      <div className="flex items-center justify-between mb-3">
                        <h3 className="text-xs font-bold text-slate-500 uppercase tracking-wider">Response</h3>
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => setShowRaw(r => !r)}
                            className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-900 transition-colors font-semibold"
                          >
                            {showRaw ? <EyeOff size={12} /> : <Layers size={12} />}
                            {showRaw ? 'Raw JSON' : 'Summary'}
                          </button>
                          <button
                            onClick={copyJSON}
                            className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-900 transition-colors font-semibold"
                          >
                            {copied ? <Check size={12} className="text-emerald-500" /> : <Copy size={12} />}
                            {copied ? 'Copied!' : 'Copy JSON'}
                          </button>
                        </div>
                      </div>
                      {showRaw ? (
                        <pre className="bg-slate-50 text-slate-700 border border-slate-100 rounded-lg p-4 text-xs font-mono overflow-auto max-h-72 leading-relaxed">
                          {JSON.stringify(response, null, 2)}
                        </pre>
                      ) : (
                        <div className="bg-slate-50 border border-slate-100 rounded-lg p-4 overflow-auto max-h-72">
                          {responseSummary}
                        </div>
                      )}
                    </div>
                  )}

                  {/* Processing Log */}
                  {procLog && procLog.length > 0 && (
                    <ProcessingLog
                      log={procLog}
                      open={showProcLog}
                      onToggle={() => setShowProcLog(v => !v)}
                    />
                  )}
                </div>
              )}
            </div>
          )}

          {/* Recent sessions (only on the setup stage) */}
          {!session && recentSessions.length > 0 && (
            <div>
              <button
                onClick={() => setShowRecent(r => !r)}
                className="flex items-center gap-2 text-sm font-bold text-slate-600 hover:text-slate-900 transition-colors mb-3"
              >
                <Clock size={13} />
                Recent calls ({recentSessions.length})
                {showRecent ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
              </button>
              {showRecent && (
                <div className="space-y-2">
                  {recentSessions.map(rec => (
                    <div
                      key={rec.id}
                      onClick={() => restoreSession(rec)}
                      className="bg-white rounded-lg border border-slate-200 px-4 py-3 flex items-center gap-4 cursor-pointer hover:border-indigo-200 hover:bg-indigo-50/30 transition-colors"
                    >
                      <div className="flex-1 min-w-0 flex items-center gap-3">
                        <span className="text-sm font-bold text-slate-900 shrink-0">{rec.brandId}</span>
                        <span className="text-slate-300">›</span>
                        <span className="text-sm text-slate-500 font-medium">{rec.targetLevel}</span>
                        <StatusBadge status={rec.finalStatus} />
                      </div>
                      <div className="text-xs text-slate-400 flex items-center gap-2">
                        <span>{rec.steps.length} step{rec.steps.length !== 1 ? 's' : ''}</span>
                        <span>·</span>
                        <span>{new Date(rec.startedAt).toLocaleTimeString()}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
