import { useState, useEffect } from 'react'
import { cn } from '../lib/utils'
import {
  Play, Send, ArrowUpRight, Loader2, XCircle, Copy, Check, RotateCcw
} from 'lucide-react'
import { type SessionRecord, type SessionStep, upsertSession, loadSessions } from '../lib/sessions'

const LEVELS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const
const TOKENS = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4'] as const

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

export default function Dashboard() {
  const [session, setSession] = useState<SessionRecord | null>(null)
  const [response, setResponse] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState('')
  const [form, setForm] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  const [copiedId, setCopiedId] = useState(false)
  const [stats, setStats] = useState({ today: 0, total: 0, brands: null as number | null })

  useEffect(() => {
    const all = loadSessions()
    const today = all.filter(s => new Date(s.startedAt).toDateString() === new Date().toDateString()).length
    setStats(s => ({ ...s, today, total: all.length }))
    fetch('/api/brands')
      .then(r => r.json())
      .then(d => setStats(s => ({ ...s, brands: Array.isArray(d) ? d.length : null })))
      .catch(() => {})
  }, [])

  const post = async (body: Record<string, unknown>): Promise<Record<string, unknown> | null> => {
    setError('')
    setLoading(true)
    try {
      const res = await fetch('/ivr/authenticate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (!res.ok) { setError(data.message || res.statusText); return null }
      return data as Record<string, unknown>
    } catch {
      setError('Network error — is the backend running?')
      return null
    } finally {
      setLoading(false)
    }
  }

  const startSession = async () => {
    if (session) return
    const payload = {
      brandId: form.brandId || 'BRAND_A',
      callerId: form.callerId || '5551234567',
      targetLevel: form.targetLevel || 'STANDARD',
    }
    const data = await post(payload)
    if (!data) return
    setResponse(data)
    const step: SessionStep = {
      at: new Date().toISOString(),
      type: 'start',
      label: `Session started → ${payload.targetLevel}`,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
    }
    const rec: SessionRecord = {
      id: String(data.sessionId ?? `local-${Date.now()}`),
      brandId: payload.brandId,
      callerId: payload.callerId,
      targetLevel: payload.targetLevel,
      startedAt: new Date().toISOString(),
      finalStatus: String(data.status ?? 'COLLECTING'),
      steps: [step],
    }
    setSession(rec)
    upsertSession(rec)
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
  }

  const submitToken = async () => {
    if (!session) return
    const payload = {
      sessionId: session.id,
      tokenType: form.tokenType || 'PIN',
      tokenValue: form.tokenValue || '',
    }
    const data = await post(payload)
    if (!data) return
    setResponse(data)
    appendStep({
      at: new Date().toISOString(),
      type: 'token',
      label: `Submitted ${payload.tokenType}`,
      response: data,
      status: String(data.status ?? 'COLLECTING'),
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
    }, data)
  }

  const reset = () => { setSession(null); setResponse(null); setError('') }

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
  const isDone = ['AUTHENTICATED', 'FAILED', 'LOCKED'].includes(currentStatus)
  const sessionActive = !!session && !isDone
  const step1Done = !!session
  const step1Bad = false
  const step2Done = isDone && currentStatus === 'AUTHENTICATED'
  const step2Bad = isDone && currentStatus !== 'AUTHENTICATED'

  const inputCls = 'w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition disabled:opacity-50 disabled:cursor-not-allowed'

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
          {stats.brands !== null && (
            <span className="bg-slate-100 text-slate-600 rounded-full px-2.5 py-1 text-xs font-semibold">
              {stats.brands} brand{stats.brands !== 1 ? 's' : ''}
            </span>
          )}
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
                  <input
                    className={inputCls}
                    placeholder="BRAND_A"
                    disabled={!!session}
                    value={form.brandId || ''}
                    onChange={e => setForm({ ...form, brandId: e.target.value })}
                  />
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
                  onClick={startSession}
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
                    onChange={e => setForm({ ...form, tokenType: e.target.value })}
                  >
                    <option value="">Select type…</option>
                    {TOKENS.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 mb-1">Token Value</label>
                  <input
                    className={inputCls}
                    placeholder="Enter value…"
                    value={form.tokenValue || ''}
                    onChange={e => setForm({ ...form, tokenValue: e.target.value })}
                    onKeyDown={e => e.key === 'Enter' && submitToken()}
                  />
                  <p className="text-[10px] text-slate-400 mt-1">Press Enter to submit quickly</p>
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
                          isAuth ? 'bg-emerald-50 border-emerald-100' :
                          isBad  ? 'bg-red-50 border-red-100' :
                                   'bg-slate-50 border-slate-100'
                        )}>
                          <div className={cn(
                            'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0',
                            isAuth ? 'bg-emerald-200 text-emerald-800' :
                            isBad  ? 'bg-red-200 text-red-700' :
                                     'bg-slate-200 text-slate-600'
                          )}>
                            {isAuth ? '✓' : isBad ? '✗' : i + 1}
                          </div>
                          <span className="flex-1 text-sm text-slate-700 font-semibold">{step.label}</span>
                          <StatusBadge status={s} />
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
                  <p className="text-xs text-slate-400 mt-1">Start a session on the left to begin testing</p>
                </div>
              )}

              {/* Latest Response */}
              {response && (
                <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
                  <div className="flex items-center justify-between mb-3">
                    <h2 className="text-sm font-bold text-slate-700">Latest Response</h2>
                    <button
                      onClick={copyJSON}
                      className="flex items-center gap-1.5 text-xs text-slate-500 hover:text-slate-900 transition-colors font-semibold"
                    >
                      {copied ? <Check size={12} className="text-emerald-500" /> : <Copy size={12} />}
                      {copied ? 'Copied!' : 'Copy JSON'}
                    </button>
                  </div>
                  <pre className="bg-slate-900 text-slate-200 rounded-lg p-4 text-xs font-mono overflow-auto max-h-72 leading-relaxed">
                    {JSON.stringify(response, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>

          {error && (
            <div className="mt-4 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-600 flex items-center gap-2">
              <XCircle size={15} className="shrink-0" />{error}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
