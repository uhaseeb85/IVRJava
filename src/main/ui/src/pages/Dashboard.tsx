import { useState, useEffect, useCallback, useRef } from 'react'
import { cn } from '../lib/utils'
import {
  Play, Send, ArrowUpRight, Loader2, XCircle, Copy, Check, RotateCcw,
  ChevronDown, ChevronUp, Clock, Eye, EyeOff, Layers, Activity
} from 'lucide-react'
import { type SessionRecord, type SessionStep, upsertSession, loadSessions } from '../lib/sessions'

const LEVELS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const
const TOKENS = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4'] as const

const TOKEN_DEFAULTS: Record<string, string> = {
  ACCOUNT_NUMBER: '123456789',
  PIN: '1234',
  OTP: '123456',
  SSN_LAST4: '1234',
  DATE_OF_BIRTH: '1985-03-15',
  CARD_LAST4: '4242',
  VOICE_PRINT: '',
}

interface Scenario {
  id: string
  label: string
  brandId: string
  callerId: string
  targetLevel: string
  isTransfer?: boolean
}

const SCENARIOS: Scenario[] = [
  { id: 'basic', label: 'Basic Auth', brandId: 'BRAND_A', callerId: '5551112222', targetLevel: 'BASIC' },
  { id: 'standard', label: 'Standard Auth', brandId: 'BRAND_A', callerId: '5551234567', targetLevel: 'STANDARD' },
  { id: 'backup', label: 'Backup Token', brandId: 'BRAND_A', callerId: '5557654321', targetLevel: 'STANDARD' },
  { id: 'fallback', label: 'Fallback Test', brandId: 'BRAND_A', callerId: '5551112222', targetLevel: 'STANDARD' },
  { id: 'transfer', label: 'Transfer', brandId: 'BRAND_A', callerId: '5551234567', targetLevel: 'STANDARD', isTransfer: true },
]

function statusStyle(status: string) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return 'text-emerald-700 bg-emerald-50 border-emerald-200'
  if (s === 'FAILED') return 'text-red-600 bg-red-50 border-red-200'
  if (s === 'LOCKED') return 'text-orange-600 bg-orange-50 border-orange-200'
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

function StepIndicator({ n, done, bad, active }: { n: number; done: boolean; bad: boolean; active: boolean }) {
  return (
    <span className={cn(
      'w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold shrink-0',
      done ? 'bg-emerald-100 text-emerald-700' :
      bad ? 'bg-red-100 text-red-600' :
      active ? 'bg-indigo-100 text-indigo-700' :
      'bg-slate-100 text-slate-400'
    )}>
      {done ? '✓' : bad ? '✗' : n}
    </span>
  )
}

function msToText(ms: number) {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export default function Dashboard() {
  const [session, setSession] = useState<SessionRecord | null>(null)
  const [response, setResponse] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState('')
  const [errorBody, setErrorBody] = useState('')
  const [form, setForm] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [copiedId, setCopiedId] = useState(false)
  const [showRaw, setShowRaw] = useState(false)
  const [showRequest, setShowRequest] = useState(false)
  const [lastRequest, setLastRequest] = useState<Record<string, unknown> | null>(null)
  const [lastTiming, setLastTiming] = useState<number | null>(null)
  // Ref so steps always capture their own timing (useState update is async/stale in closures)
  const timingRef = useRef<number>(0)
  const [stats, setStats] = useState({ today: 0, total: 0, brands: [] as { brandId: string }[] })
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

  const startSession = async (override?: Partial<{ brandId: string; callerId: string; targetLevel: string; isTransfer: boolean }>) => {
    if (session && !override) return
    const payload: Record<string, unknown> = {
      brandId: override?.brandId || form.brandId || 'BRAND_A',
      callerId: override?.callerId || form.callerId || '5551234567',
      targetLevel: override?.targetLevel || form.targetLevel || 'STANDARD',
    }
    if (override?.isTransfer) {
      payload.sourceSystemId = 'LEGACY_IVR'
      payload.currentLevel = 'NONE'
      payload.validatedTokens = ['ACCOUNT_NUMBER']
    }
    // Pre-populate form fields if set from scenario
    if (override) {
      setForm(f => ({
        ...f,
        brandId: override.brandId ?? f.brandId,
        callerId: override.callerId ?? f.callerId,
        targetLevel: override.targetLevel ?? f.targetLevel,
      }))
    }
    const data = await post(payload)
    if (!data) return
    setResponse(data)
    const step: SessionStep = {
      at: new Date().toISOString(),
      type: 'start',
      label: `Session started → ${payload.targetLevel}${override?.isTransfer ? ' (transfer)' : ''}`,
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
      ? `${payload.tokenType} rejected${remaining != null
          ? ` — ${remaining} attempt${remaining === 1 ? '' : 's'} left`
          : ''}`
      : `Submitted ${payload.tokenType}`

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

  const escalate = async () => {
    if (!session) return
    const level = form.escalateLevel || 'ELEVATED'
    const data = await post({ sessionId: session.id, targetLevel: level })
    if (!data) return
    setResponse(data)
    appendStep({
      at: new Date().toISOString(),
      type: 'escalate',
      label: `Escalated → ${level}`,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
      timing: timingRef.current || undefined,
    }, data)
  }

  const reset = () => { setSession(null); setResponse(null); setError(''); setErrorBody(''); setLastRequest(null); setLastTiming(null) }

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

  const currentStatus = session?.finalStatus?.toUpperCase() ?? 'IDLE'
  const isDone = ['AUTHENTICATED', 'FAILED', 'LOCKED'].includes(currentStatus)
  const sessionActive = !!session && !isDone
  const step1Done = !!session
  const step1Bad = false
  const step2Done = isDone && currentStatus === 'AUTHENTICATED'
  const step2Bad = isDone && currentStatus !== 'AUTHENTICATED'

  const inputCls = 'w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition disabled:opacity-50 disabled:cursor-not-allowed'

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
          <p className="text-sm text-slate-500 mt-0.5">Simulate IVR authentication flows end-to-end</p>
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
              <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider">Session</span>
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
              <RotateCcw size={12} />Reset
            </button>
          </div>
        )}
      </div>

      {/* Scenario pills */}
      {!session && (
        <div className="bg-white border-b border-slate-100 px-8 py-3 flex items-center gap-2 overflow-x-auto shrink-0">
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mr-1 shrink-0">Scenarios</span>
          {SCENARIOS.map(s => (
            <button
              key={s.id}
              onClick={() => startSession({ brandId: s.brandId, callerId: s.callerId, targetLevel: s.targetLevel, isTransfer: s.isTransfer })}
              disabled={loading}
              className="shrink-0 rounded-full border border-slate-200 bg-white px-3.5 py-1.5 text-xs font-semibold text-slate-600 hover:border-indigo-300 hover:text-indigo-700 hover:bg-indigo-50 transition-colors disabled:opacity-50"
            >
              {s.label}
            </button>
          ))}
        </div>
      )}

      {/* Content */}
      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-5xl mx-auto">
          <div className="grid grid-cols-5 gap-6">

            {/* ── Left: Controls (2 of 5) ── */}
            <div className="col-span-2 space-y-4">

              {/* Step 1 — Start Session */}
              <div className={cn(
                'bg-white rounded-xl border shadow-sm p-5 space-y-3.5 transition-opacity duration-200',
                step1Done ? 'border-slate-100 opacity-60' : 'border-slate-200'
              )}>
                <div className="flex items-center gap-2.5">
                  <StepIndicator n={1} done={step1Done} bad={step1Bad} active={!step1Done} />
                  <span className="text-sm font-bold text-slate-800">Start Session</span>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Brand ID</label>
                  {stats.brands.length > 0 ? (
                    <select
                      className={inputCls}
                      disabled={!!session}
                      value={form.brandId || ''}
                      onChange={e => setForm({ ...form, brandId: e.target.value })}
                    >
                      <option value="">Select brand…</option>
                      {stats.brands.map(b => (
                        <option key={b.brandId} value={b.brandId}>{b.brandId}</option>
                      ))}
                    </select>
                  ) : (
                    <input
                      className={inputCls}
                      placeholder="BRAND_A"
                      disabled={!!session}
                      value={form.brandId || ''}
                      onChange={e => setForm({ ...form, brandId: e.target.value })}
                    />
                  )}
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Caller ID</label>
                  <input
                    className={inputCls}
                    placeholder="5551234567"
                    disabled={!!session}
                    value={form.callerId || ''}
                    onChange={e => setForm({ ...form, callerId: e.target.value })}
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Target Level</label>
                  <select
                    className={inputCls}
                    disabled={!!session}
                    value={form.targetLevel || ''}
                    onChange={e => setForm({ ...form, targetLevel: e.target.value })}
                  >
                    <option value="">Select level…</option>
                    {LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
                  </select>
                </div>
                <button
                  onClick={() => startSession()}
                  disabled={loading || !!session}
                  className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2 shadow-sm"
                >
                  {loading && !session ? <Loader2 size={15} className="animate-spin" /> : <Play size={15} />}
                  Start Session
                </button>
              </div>

              {/* Step 2 — Submit Token */}
              <div className={cn(
                'bg-white rounded-xl border border-slate-200 shadow-sm p-5 space-y-3.5 transition-opacity duration-200',
                !session || isDone ? 'opacity-50 pointer-events-none' : ''
              )}>
                <div className="flex items-center gap-2.5">
                  <StepIndicator n={2} done={step2Done} bad={step2Bad} active={sessionActive} />
                  <span className="text-sm font-bold text-slate-800">Submit Token</span>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Token Type</label>
                  <select
                    className={inputCls}
                    value={form.tokenType || ''}
                    onChange={e => {
                      const tt = e.target.value
                      setForm({ ...form, tokenType: tt, tokenValue: TOKEN_DEFAULTS[tt] ?? '' })
                    }}
                  >
                    <option value="">Select type…</option>
                    {TOKENS.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Token Value</label>
                  <input
                    className={inputCls}
                    placeholder={`e.g. ${TOKEN_DEFAULTS[form.tokenType || 'PIN']}`}
                    value={form.tokenValue || ''}
                    onChange={e => setForm({ ...form, tokenValue: e.target.value })}
                    onKeyDown={e => e.key === 'Enter' && submitToken()}
                  />
                  <p className="text-[10px] text-slate-400 mt-1">Press Enter · Ctrl+Enter from anywhere</p>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Escalate To</label>
                  <select
                    className={inputCls}
                    value={form.escalateLevel || ''}
                    onChange={e => setForm({ ...form, escalateLevel: e.target.value })}
                  >
                    <option value="">Select level…</option>
                    {LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
                  </select>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={submitToken}
                    disabled={loading || !sessionActive}
                    className="flex-1 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2 shadow-sm"
                  >
                    {loading && !!session ? <Loader2 size={15} className="animate-spin" /> : <Send size={14} />}
                    Submit
                  </button>
                  <button
                    onClick={escalate}
                    disabled={loading || !sessionActive}
                    className="rounded-lg border border-slate-200 bg-white px-3 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5 shadow-sm"
                  >
                    <ArrowUpRight size={14} />Escalate
                  </button>
                </div>
              </div>

              {/* Remaining Attempts counter — visible whenever the session is collecting retries */}
              {sessionActive && response?.remainingAttempts != null && (
                <div className={cn(
                  'rounded-xl border p-4 flex items-center justify-between transition-colors',
                  (response.remainingAttempts as number) <= 1
                    ? 'bg-red-50 border-red-200'
                    : 'bg-amber-50 border-amber-200'
                )}>
                  <div>
                    <p className={cn(
                      'text-[10px] font-bold uppercase tracking-widest',
                      (response.remainingAttempts as number) <= 1 ? 'text-red-500' : 'text-amber-600'
                    )}>Remaining Attempts</p>
                    <p className={cn(
                      'text-xs mt-0.5',
                      (response.remainingAttempts as number) <= 1 ? 'text-red-400' : 'text-amber-500'
                    )}>for {String(response.nextRequiredToken ?? 'current token')}</p>
                  </div>
                  <span className={cn(
                    'text-4xl font-black tabular-nums leading-none',
                    (response.remainingAttempts as number) <= 1 ? 'text-red-600' : 'text-amber-600'
                  )}>
                    {response.remainingAttempts as number}
                  </span>
                </div>
              )}
            </div>

            {/* ── Right: Flow + Response (3 of 5) ── */}
            <div className="col-span-3 space-y-4">

              {/* Auth Flow Steps */}
              {session && session.steps.length > 0 ? (
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
                  <h2 className="text-sm font-bold text-slate-700 mb-4">Auth Flow</h2>
                  <div className="space-y-2">
                    {session.steps.map((step, i) => {
                      const s = step.status.toUpperCase()
                      const isAuth = s === 'AUTHENTICATED'
                      const isBad = s === 'FAILED' || s === 'LOCKED'
                      return (
                        <div key={i} className={cn(
                          'flex items-center gap-3 rounded-lg px-3 py-2.5 border',
                          isAuth      ? 'bg-emerald-50 border-emerald-100' :
                          isBad       ? 'bg-red-50 border-red-100' :
                          step.failed ? 'bg-amber-50 border-amber-100' :
                                        'bg-slate-50 border-slate-100'
                        )}>
                          <div className={cn(
                            'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0',
                            isAuth      ? 'bg-emerald-200 text-emerald-800' :
                            isBad       ? 'bg-red-200 text-red-700' :
                            step.failed ? 'bg-amber-200 text-amber-800' :
                                          'bg-slate-200 text-slate-600'
                          )}>
                            {isAuth ? '✓' : isBad ? '✗' : step.failed ? '✗' : i + 1}
                          </div>
                          <span className={cn(
                            'flex-1 text-sm font-semibold',
                            step.failed ? 'text-amber-800' : 'text-slate-700'
                          )}>{step.label}</span>
                          {/* "N left" pill — shown for rejected token steps */}
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
              ) : (
                <div className="bg-white rounded-xl border border-dashed border-slate-200 p-12 text-center">
                  <div className="w-10 h-10 rounded-xl bg-slate-100 flex items-center justify-center mx-auto mb-3">
                    <Play size={18} className="text-slate-400" />
                  </div>
                  <p className="text-sm font-semibold text-slate-500">No active session</p>
                  <p className="text-xs text-slate-400 mt-1">Start a session or pick a scenario above</p>
                </div>
              )}

              {/* Request preview */}
              {lastRequest && (
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                  <button
                    onClick={() => setShowRequest(r => !r)}
                    className="w-full px-5 py-3 flex items-center justify-between hover:bg-slate-50 transition-colors"
                  >
                    <div className="flex items-center gap-2 text-sm font-bold text-slate-700">
                      <Eye size={13} />
                      Request sent
                      {lastTiming != null && (
                        <span className="text-[10px] font-normal text-slate-400 ml-1">({msToText(lastTiming)})</span>
                      )}
                    </div>
                    {showRequest ? <ChevronUp size={14} className="text-slate-400" /> : <ChevronDown size={14} className="text-slate-400" />}
                  </button>
                  {showRequest && (
                    <pre className="bg-slate-50 border-t border-slate-100 text-slate-700 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
                      {JSON.stringify(lastRequest, null, 2)}
                    </pre>
                  )}
                </div>
              )}

              {/* Error with expander */}
              {error && (
                <div className="bg-white rounded-xl border border-red-200 shadow-sm overflow-hidden">
                  <button
                    onClick={() => errorBody ? setErrorBody('') : setErrorBody('(no details)')}
                    className="w-full p-4 flex items-center gap-2 text-sm text-red-600 hover:bg-red-50 transition-colors text-left"
                  >
                    <XCircle size={15} className="shrink-0" />
                    <span className="flex-1 font-medium">{error}</span>
                    {errorBody && (
                      errorBody === '(no details)' ? <ChevronUp size={14} /> : <ChevronDown size={14} />
                    )}
                  </button>
                  {errorBody && errorBody !== '(no details)' && (
                    <pre className="bg-red-50 border-t border-red-100 text-red-800 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
                      {errorBody}
                    </pre>
                  )}
                </div>
              )}

              {/* Response */}
              {response && (
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
                  <div className="flex items-center justify-between mb-3">
                    <h2 className="text-sm font-bold text-slate-700">Response</h2>
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

              {/* ── Processing Log ── */}
              {(() => {
                const procLog = response?.processingLog as Array<{ level: string; message: string }> | undefined
                if (!procLog || procLog.length === 0) return null
                const levelStyle = (level: string) => {
                  const l = level.toUpperCase()
                  if (l === 'PASS') return { badge: 'bg-emerald-100 text-emerald-700', text: 'text-emerald-800' }
                  if (l === 'FAIL') return { badge: 'bg-red-100 text-red-600',     text: 'text-red-700' }
                  if (l === 'WARN') return { badge: 'bg-amber-100 text-amber-700', text: 'text-amber-800' }
                  return              { badge: 'bg-slate-100 text-slate-500',  text: 'text-slate-600' }
                }
                return (
                  <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                    <button
                      onClick={() => setShowProcLog(v => !v)}
                      className="w-full px-5 py-3 flex items-center justify-between hover:bg-slate-50 transition-colors"
                    >
                      <div className="flex items-center gap-2 text-sm font-bold text-slate-700">
                        <Activity size={13} className="text-indigo-500" />
                        Processing Log
                        <span className="text-[10px] font-normal text-slate-400 ml-1">
                          ({procLog.length} event{procLog.length !== 1 ? 's' : ''})
                        </span>
                      </div>
                      {showProcLog
                        ? <ChevronUp size={14} className="text-slate-400" />
                        : <ChevronDown size={14} className="text-slate-400" />}
                    </button>
                    {showProcLog && (
                      <div className="border-t border-slate-100 divide-y divide-slate-50">
                        {procLog.map((entry, i) => {
                          const st = levelStyle(entry.level)
                          return (
                            <div key={i} className="flex items-start gap-3 px-4 py-2.5">
                              <span className={cn(
                                'shrink-0 rounded px-1.5 py-0.5 text-[10px] font-black w-9 text-center mt-0.5',
                                st.badge
                              )}>
                                {entry.level.toUpperCase()}
                              </span>
                              <span className={cn('text-xs leading-relaxed font-mono', st.text)}>
                                {entry.message}
                              </span>
                            </div>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })()}
            </div>
          </div>

          {/* Inline recent sessions */}
          {recentSessions.length > 0 && (
            <div className="mt-6">
              <button
                onClick={() => setShowRecent(r => !r)}
                className="flex items-center gap-2 text-sm font-bold text-slate-600 hover:text-slate-900 transition-colors mb-3"
              >
                <Clock size={13} />
                Recent Sessions ({recentSessions.length})
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
