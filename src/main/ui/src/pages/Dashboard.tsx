import { useState, useEffect, useCallback, useRef } from 'react'
import { Copy, Check, RotateCcw } from 'lucide-react'
import { type SessionRecord, type SessionStep, upsertSession, loadSessions } from '../lib/sessions'
import { LEVEL_ORDER, TOKEN_DEFAULTS, SAMPLE_CALLERS, tokenLabel, levelsForBrand } from '../lib/ivrMeta'
import { type BrandSummary, getBrands } from '../lib/api'
import StatusBadge from '../components/StatusBadge'
import CallSetupCard from '../components/dashboard/CallSetupCard'
import ErrorBanner from '../components/dashboard/ErrorBanner'
import OnCallCard from '../components/dashboard/OnCallCard'
import DevDetailsPanel from '../components/dashboard/DevDetailsPanel'
import RecentSessionsList from '../components/dashboard/RecentSessionsList'

// Test Console orchestrator: owns all session/form/UI state and the API
// handlers, and composes the three stages (setup → on-call → dev details)
// from components/dashboard/.
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
    getBrands()
      .then(brands => setStats(s => ({ ...s, brands })))
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

  return (
    <div className="flex flex-col min-h-full">
      {/* Page header */}
      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center gap-6 shrink-0">
        <div className="flex-1 min-w-0">
          <p className="text-sm text-slate-500 dark:text-slate-400">Simulate a caller phoning in and getting verified — step by step</p>
        </div>

        {/* Active session pill */}
        {session && (
          <div className="flex items-center gap-3 pl-4 border-l border-slate-200 dark:border-slate-700">
            <div className="flex items-center gap-2 bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg px-3 py-1.5">
              <span className="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider">Call</span>
              <code className="text-xs font-mono text-indigo-600 dark:text-indigo-400">{session.id.slice(0, 10)}…</code>
              <button onClick={copyId} className="text-slate-400 dark:text-slate-500 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors">
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

          {/* STAGE 1 — Who's calling? */}
          {!session && (
            <CallSetupCard
              brands={stats.brands}
              form={form}
              setForm={setForm}
              idOnly={idOnly}
              brandLevels={brandLevels}
              canPlaceCall={canPlaceCall}
              loading={loading}
              showAdvanced={showAdvanced}
              setShowAdvanced={setShowAdvanced}
              onPlaceCall={placeCall}
            />
          )}

          {/* Network / server error (always prominent) */}
          {error && (
            <ErrorBanner error={error} errorBody={errorBody} setErrorBody={setErrorBody} />
          )}

          {/* STAGE 2 — On the call */}
          {session && (
            <OnCallCard
              session={session}
              response={response}
              form={form}
              setForm={setForm}
              loading={loading}
              idOnly={idOnly}
              currentStatus={currentStatus}
              currentLevel={currentLevel}
              sessionActive={sessionActive}
              identified={identified}
              higherLevels={higherLevels}
              activeToken={activeToken}
              alternatives={alternatives}
              onSubmitToken={submitToken}
              onEscalate={escalate}
              onReset={reset}
            />
          )}

          {/* STAGE 3 — Developer details */}
          {(session || response) && (
            <DevDetailsPanel
              session={session}
              response={response}
              lastRequest={lastRequest}
              lastTiming={lastTiming}
              procLog={procLog}
              showDev={showDev}
              setShowDev={setShowDev}
              showRequest={showRequest}
              setShowRequest={setShowRequest}
              showRaw={showRaw}
              setShowRaw={setShowRaw}
              showProcLog={showProcLog}
              setShowProcLog={setShowProcLog}
              copied={copied}
              onCopyJSON={copyJSON}
            />
          )}

          {/* Recent sessions (only on the setup stage) */}
          {!session && recentSessions.length > 0 && (
            <RecentSessionsList
              sessions={recentSessions}
              show={showRecent}
              setShow={setShowRecent}
              onRestore={restoreSession}
            />
          )}
        </div>
      </div>
    </div>
  )
}
