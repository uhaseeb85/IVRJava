import { useState, useEffect } from 'react'
import { History, ChevronDown, ChevronUp, Trash2 } from 'lucide-react'
import { loadSessions, clearSessions, type SessionRecord } from '../lib/sessions'
import { cn } from '../lib/utils'
import StatusBadge from '../components/StatusBadge'
import EmptyState from '../components/EmptyState'

function stepIcon(status: string, index: number) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return { symbol: '✓', cls: 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300' }
  if (s === 'FAILED' || s === 'REDIRECT_TO_AGENT') return { symbol: '✗', cls: 'bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-400' }
  return { symbol: String(index + 1), cls: 'bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300' }
}

function SessionCard({ rec }: { rec: SessionRecord }) {
  const [expanded, setExpanded] = useState(false)
  const time = new Date(rec.startedAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })

  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
      <div
        className="px-5 py-4 flex items-center gap-4 cursor-pointer hover:bg-slate-50/80 dark:hover:bg-slate-750 transition-colors"
        onClick={() => setExpanded(e => !e)}
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2.5 flex-wrap">
            <span className="font-bold text-slate-900 dark:text-slate-100">{rec.brandId}</span>
            <span className="text-slate-300 dark:text-slate-600">›</span>
            <span className="text-sm text-slate-600 dark:text-slate-400 font-medium">{rec.targetLevel}</span>
            <StatusBadge status={rec.finalStatus} />
          </div>
          <div className="text-xs text-slate-400 dark:text-slate-500 mt-1 flex items-center gap-2">
            <span className="font-mono">{rec.callerId}</span>
            <span>·</span>
            <span>{rec.steps.length} step{rec.steps.length !== 1 ? 's' : ''}</span>
            <span>·</span>
            <span>{time}</span>
            <span>·</span>
            <span className="font-mono text-[10px] text-slate-300 dark:text-slate-600">{rec.id.slice(0, 8)}…</span>
          </div>
        </div>
        <button className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors shrink-0 p-1">
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-slate-100 dark:border-slate-700 bg-slate-50/60 dark:bg-slate-800/50 px-5 py-4 space-y-2.5">
          {rec.steps.map((step, i) => {
            const { symbol, cls } = stepIcon(step.status, i)
            return (
              <div key={i} className="flex items-center gap-3">
                <div className={cn('w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0', cls)}>
                  {symbol}
                </div>
                <span className="text-sm text-slate-700 dark:text-slate-300 flex-1 font-medium">{step.label}</span>
                <StatusBadge status={step.status} />
                <span className="text-[10px] text-slate-400 dark:text-slate-500 font-mono tabular-nums">
                  {new Date(step.at).toLocaleTimeString()}
                </span>
              </div>
            )
          })}

          {rec.steps.length > 0 && (
            <details className="pt-1">
              <summary className="text-xs text-slate-500 dark:text-slate-400 cursor-pointer hover:text-slate-700 dark:hover:text-slate-300 font-medium select-none">
                View final response
              </summary>
              <pre className="mt-2 bg-slate-900 dark:bg-slate-950 text-slate-200 rounded-lg p-3 text-xs font-mono overflow-auto max-h-52 leading-relaxed">
                {JSON.stringify(rec.steps[rec.steps.length - 1]?.response, null, 2)}
              </pre>
            </details>
          )}
        </div>
      )}
    </div>
  )
}

function groupByDate(sessions: SessionRecord[]) {
  const today = new Date().toDateString()
  const yesterday = new Date(Date.now() - 86400000).toDateString()
  const map = new Map<string, SessionRecord[]>()

  for (const s of sessions) {
    const d = new Date(s.startedAt).toDateString()
    const label =
      d === today ? 'Today' :
      d === yesterday ? 'Yesterday' :
      new Date(s.startedAt).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })
    if (!map.has(label)) map.set(label, [])
    map.get(label)!.push(s)
  }

  return Array.from(map.entries()).map(([label, items]) => ({ label, items }))
}

export default function SessionLog() {
  const [sessions, setSessions] = useState<SessionRecord[]>([])
  const [search, setSearch] = useState('')

  useEffect(() => { setSessions(loadSessions()) }, [])

  const handleClear = () => {
    if (!confirm('Clear all session history? This cannot be undone.')) return
    clearSessions()
    setSessions([])
  }

  const filtered = sessions.filter(s =>
    !search || s.brandId.toLowerCase().includes(search.toLowerCase()) ||
    s.finalStatus.toLowerCase().includes(search.toLowerCase())
  )

  const groups = groupByDate(filtered)
  const todayCount = sessions.filter(s => new Date(s.startedAt).toDateString() === new Date().toDateString()).length

  return (
    <div className="flex flex-col min-h-full">
      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Session Log</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {sessions.length} session{sessions.length !== 1 ? 's' : ''} recorded · {todayCount} today
          </p>
        </div>
        {sessions.length > 0 && (
          <button
            onClick={handleClear}
            className="flex items-center gap-1.5 text-sm text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:text-red-400 transition-colors font-medium"
          >
            <Trash2 size={14} />Clear History
          </button>
        )}
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-3xl mx-auto space-y-6">
          {sessions.length > 0 && (
            <input
              className="w-full max-w-xs rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3.5 py-2.5 text-sm dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition shadow-sm"
              placeholder="Filter by brand or status…"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          )}

          {sessions.length === 0 ? (
            <EmptyState
              icon={History}
              title="No session history yet"
              subtitle="Test sessions from the Dashboard will appear here automatically"
            />
          ) : filtered.length === 0 ? (
            <p className="text-sm text-slate-400 dark:text-slate-500 text-center py-8">No sessions match your filter</p>
          ) : (
            groups.map(({ label, items }) => (
              <div key={label}>
                <div className="flex items-center gap-3 mb-3">
                  <span className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest">{label}</span>
                  <div className="flex-1 h-px bg-slate-200 dark:bg-slate-700" />
                  <span className="text-xs text-slate-400 dark:text-slate-500">{items.length}</span>
                </div>
                <div className="space-y-2.5">
                  {items.map(rec => <SessionCard key={rec.id} rec={rec} />)}
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
