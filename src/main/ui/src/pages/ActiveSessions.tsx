import { useState, useEffect } from 'react'
import { PhoneOff, RefreshCw, Search, XCircle, Trash2, Phone, ChevronDown, ChevronUp } from 'lucide-react'
import { cn } from '../lib/utils'
import { getActiveSessions, searchSessions, deleteSession, type SessionSummary } from '../lib/api'
import EmptyState from '../components/EmptyState'
import StatusBadge from '../components/StatusBadge'

const STATUSES = ['', 'COLLECTING', 'AUTHENTICATED', 'FAILED', 'REDIRECT_TO_AGENT', 'EXPIRED'] as const

function SessionRow({ session, onRefresh }: { session: SessionSummary; onRefresh: () => void }) {
  const [expanded, setExpanded] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const handleDelete = async () => {
    if (!confirm(`Force-end session ${session.sessionId.slice(0, 8)}…?`)) return
    setDeleting(true)
    try { await deleteSession(session.sessionId); onRefresh() }
    catch { setDeleting(false) }
  }

  const time = new Date(session.createdAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
  const date = new Date(session.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })

  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
      <div
        className="px-5 py-4 flex items-center gap-4 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-750 transition-colors"
        onClick={() => setExpanded(e => !e)}
      >
        <div className="w-9 h-9 rounded-lg bg-indigo-100 dark:bg-indigo-900/50 flex items-center justify-center shrink-0">
          <Phone size={15} className="text-indigo-600 dark:text-indigo-400" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2.5 flex-wrap">
            <span className="font-bold text-slate-900 dark:text-slate-100">{session.brandId}</span>
            <span className="text-slate-300 dark:text-slate-600">·</span>
            <span className="text-xs font-mono text-slate-500 dark:text-slate-400">{session.callerId}</span>
            <span className="text-slate-300 dark:text-slate-600">·</span>
            <StatusBadge status={session.status} />
          </div>
          <div className="text-xs text-slate-400 dark:text-slate-500 mt-1 flex items-center gap-2">
            <span>{session.currentLevel} → {session.targetLevel}</span>
            <span>·</span>
            <span>{session.phase}</span>
            {session.transferredFrom && (
              <>
                <span>·</span>
                <span className="text-amber-600 dark:text-amber-400">transferred from {session.transferredFrom}</span>
              </>
            )}
            <span>·</span>
            <span>{date} {time}</span>
            <span>·</span>
            <span className="font-mono text-[10px] text-slate-400">{session.sessionId.slice(0, 8)}…</span>
          </div>
        </div>
        <button
          onClick={e => { e.stopPropagation(); handleDelete() }}
          disabled={deleting}
          className="text-slate-400 hover:text-red-600 transition-colors p-1 shrink-0 disabled:opacity-50"
          title="Force-end session"
        >
          <Trash2 size={14} />
        </button>
        <button className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors shrink-0 p-1">
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 px-5 py-4 space-y-3">
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3 text-xs">
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Session ID</span>
              <p className="font-mono text-slate-700 dark:text-slate-300 mt-0.5 break-all">{session.sessionId}</p>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Current Level</span>
              <p className="text-slate-700 dark:text-slate-300 mt-0.5">{session.currentLevel}</p>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Target Level</span>
              <p className="text-slate-700 dark:text-slate-300 mt-0.5">{session.targetLevel}</p>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Phase</span>
              <p className="text-slate-700 dark:text-slate-300 mt-0.5">{session.phase}</p>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Validated Tokens</span>
              <p className="text-slate-700 dark:text-slate-300 mt-0.5">
                {session.validatedTokens && session.validatedTokens.length > 0
                  ? session.validatedTokens.join(', ')
                  : 'None'}
              </p>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 font-semibold">Locked Until</span>
              <p className="text-slate-700 dark:text-slate-300 mt-0.5">
                {session.lockedUntil ? new Date(session.lockedUntil).toLocaleString() : '—'}
              </p>
            </div>
          </div>
          {session.matchedParty && (
            <div>
              <span className="text-xs text-slate-500 dark:text-slate-400 font-semibold">Matched Party</span>
              <pre className="mt-1 bg-slate-900 dark:bg-slate-950 text-slate-200 rounded-lg p-2 text-[10px] font-mono overflow-auto max-h-32">
                {JSON.stringify(session.matchedParty, null, 2)}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default function ActiveSessions() {
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [filterBrand, setFilterBrand] = useState('')
  const [filterStatus, setFilterStatus] = useState('')
  const [filterCaller, setFilterCaller] = useState('')

  const load = async (brand?: string, status?: string, caller?: string) => {
    setLoading(true)
    try {
      const hasFilters = brand || status || caller
      const data = hasFilters
        ? await searchSessions({ brandId: brand, status, callerId: caller })
        : await getActiveSessions()
      setSessions(data)
    } catch {
      setSessions([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    load(filterBrand || undefined, filterStatus || undefined, filterCaller || undefined)
  }

  return (
    <div className="flex flex-col min-h-full">
      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Active Sessions</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {loading ? 'Loading…' : `${sessions.length} active session${sessions.length !== 1 ? 's' : ''}`}
          </p>
        </div>
        <button
          onClick={() => load()}
          className="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-slate-600 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
        >
          <RefreshCw size={15} />Refresh
        </button>
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-4xl mx-auto space-y-6">
          {/* Search */}
          <form onSubmit={handleSearch} className="flex flex-wrap items-end gap-3">
            <div>
              <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">Brand</label>
              <input
                className="rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 placeholder:text-slate-400 w-32 focus:outline-none focus:ring-2 focus:ring-indigo-500/30"
                placeholder="Any brand"
                value={filterBrand}
                onChange={e => setFilterBrand(e.target.value)}
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">Status</label>
              <select
                className="rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/30"
                value={filterStatus}
                onChange={e => setFilterStatus(e.target.value)}
              >
                {STATUSES.map(s => (
                  <option key={s} value={s}>{s || 'Any status'}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">Caller ID</label>
              <input
                className="rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 placeholder:text-slate-400 w-36 focus:outline-none focus:ring-2 focus:ring-indigo-500/30"
                placeholder="Any caller"
                value={filterCaller}
                onChange={e => setFilterCaller(e.target.value)}
              />
            </div>
            <button
              type="submit"
              className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 transition-colors shadow-sm"
            >
              <Search size={14} />Search
            </button>
            {(filterBrand || filterStatus || filterCaller) && (
              <button
                type="button"
                onClick={() => { setFilterBrand(''); setFilterStatus(''); setFilterCaller(''); load() }}
                className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
              >
                <XCircle size={14} />Clear
              </button>
            )}
          </form>

          {loading ? (
            <div className="space-y-3">
              {[1, 2, 3].map(i => (
                <div key={i} className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5 animate-pulse">
                  <div className="h-4 w-48 bg-slate-200 dark:bg-slate-700 rounded mb-2" />
                  <div className="h-3 w-64 bg-slate-100 dark:bg-slate-700 rounded" />
                </div>
              ))}
            </div>
          ) : sessions.length === 0 ? (
            <EmptyState
              icon={PhoneOff}
              title="No active sessions"
              subtitle="Active authentication sessions will appear here in real time"
            />
          ) : (
            <div className="space-y-2.5">
              {sessions.map(s => (
                <SessionRow key={s.sessionId} session={s} onRefresh={() => load()} />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
