import { useState } from 'react'
import { cn } from '../lib/utils'

type Status = 'idle' | 'collecting' | 'authenticated' | 'failed' | 'locked'

interface FieldDef { key: string; label: string; type: string; placeholder?: string }

const START_FIELDS: FieldDef[] = [
  { key: 'brandId', label: 'Brand ID', type: 'text', placeholder: 'BRAND_A' },
  { key: 'callerId', label: 'Caller ID', type: 'text', placeholder: '5551234567' },
  { key: 'targetLevel', label: 'Target Level', type: 'select' },
]

const TOKEN_FIELDS: FieldDef[] = [
  { key: 'tokenType', label: 'Token Type', type: 'select' },
  { key: 'tokenValue', label: 'Token Value', type: 'text', placeholder: '1234' },
]

const LVLS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN']
const TKNS = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4']

export default function Dashboard() {
  const [sessionId, setSessionId] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  const [response, setResponse] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState('')
  const [form, setForm] = useState<Record<string, string>>({})

  const post = async (body: Record<string, unknown>) => {
    setError('')
    try {
      const res = await fetch('/ivr/authenticate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
      const data = await res.json()
      if (!res.ok) { setError(data.message || res.statusText); return null }
      return data
    } catch { setError('Network error'); return null }
  }

  const startSession = async () => {
    const data = await post({
      brandId: form.brandId || 'BRAND_A',
      callerId: form.callerId || '5551234567',
      targetLevel: form.targetLevel || 'STANDARD'
    })
    if (data) {
      setResponse(data)
      setSessionId(data.sessionId)
      setStatus(data.status === 'AUTHENTICATED' ? 'authenticated' : 'collecting')
    }
  }

  const submitToken = async () => {
    const data = await post({
      sessionId,
      tokenType: form.tokenType || 'PIN',
      tokenValue: form.tokenValue || '1234'
    })
    if (data) {
      setResponse(data)
      const s = data.status as string
      if (s === 'AUTHENTICATED') setStatus('authenticated')
      else if (s === 'FAILED' || s === 'LOCKED') setStatus(s.toLowerCase() as Status)
      else setStatus('collecting')
    }
  }

  const escalate = async () => {
    const data = await post({
      sessionId,
      targetLevel: form.targetLevel || 'ELEVATED'
    })
    if (data) {
      setResponse(data)
      setStatus(data.status === 'AUTHENTICATED' ? 'authenticated' : 'collecting')
    }
  }

  const statusStyle = cn(
    'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold',
    status === 'authenticated' && 'bg-emerald-500/20 text-emerald-400',
    status === 'collecting' && 'bg-amber-500/20 text-amber-400',
    status === 'failed' && 'bg-red-500/20 text-red-400',
    status === 'locked' && 'bg-orange-500/20 text-orange-400',
    status === 'idle' && 'bg-zinc-500/20 text-zinc-400',
  )

  const inputCls = 'w-full rounded-lg border border-zinc-700 bg-zinc-800/50 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/50'

  return (
    <div className="p-8 max-w-4xl mx-auto space-y-8">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
        <p className="text-sm text-zinc-400 mt-1">Test the authentication flow end-to-end</p>
      </div>

      {sessionId && (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-4">
          <div className="flex items-center gap-3">
            <span className="text-sm text-zinc-400">Session</span>
            <code className="text-sm font-mono text-indigo-400">{sessionId}</code>
            <span className={statusStyle}>{status.toUpperCase()}</span>
            <button
              onClick={() => { setSessionId(''); setStatus('idle'); setResponse(null); setError('') }}
              className="ml-auto text-xs text-zinc-500 hover:text-zinc-300"
            >Reset</button>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Start */}
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 space-y-4">
          <h3 className="font-semibold text-sm text-zinc-300">Start Session</h3>
          {START_FIELDS.map(f => f.type === 'select' ? (
            <select key={f.key} className={inputCls} value={form[f.key] || ''}
              onChange={e => setForm({ ...form, [f.key]: e.target.value })}>
              <option value="">- {f.label} -</option>
              {LVLS.map(l => <option key={l} value={l}>{l}</option>)}
            </select>
          ) : (
            <input key={f.key} className={inputCls} placeholder={f.placeholder}
              value={form[f.key] || ''} onChange={e => setForm({ ...form, [f.key]: e.target.value })} />
          ))}
          <button
            onClick={startSession}
            className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors"
          >Start</button>
        </div>

        {/* Submit Token */}
        {sessionId && (
          <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 space-y-4">
            <h3 className="font-semibold text-sm text-zinc-300">Submit Token</h3>
            <select className={inputCls} value={form.tokenType || ''}
              onChange={e => setForm({ ...form, tokenType: e.target.value })}>
              <option value="">- Token Type -</option>
              {TKNS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
            <input className={inputCls} placeholder="Token value" value={form.tokenValue || ''}
              onChange={e => setForm({ ...form, tokenValue: e.target.value })} />
            <div className="flex gap-2">
              <button onClick={submitToken}
                className="flex-1 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors"
              >Submit</button>
              <button onClick={escalate}
                className="rounded-lg border border-zinc-700 px-4 py-2.5 text-sm font-medium text-zinc-300 hover:bg-zinc-800 transition-colors"
              >Escalate</button>
            </div>
          </div>
        )}
      </div>

      {error && (
        <div className="rounded-xl border border-red-900/50 bg-red-950/30 p-4 text-sm text-red-400">{error}</div>
      )}

      {response && (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5">
          <h3 className="font-semibold text-sm text-zinc-300 mb-3">Response</h3>
          <pre className="text-xs text-zinc-300 font-mono overflow-auto max-h-96 whitespace-pre-wrap">
            {JSON.stringify(response, null, 2)}
          </pre>
        </div>
      )}
    </div>
  )
}
