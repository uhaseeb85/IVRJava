import { useState, useEffect } from 'react'
import { History, ChevronDown, ChevronUp, Trash2 } from 'lucide-react'
import { loadSessions, clearSessions, type SessionRecord } from '../lib/sessions'
import { cn } from '../lib/utils'

function statusCls(status: string) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return 'text-emerald-700 bg-emerald-50 border-emerald-200'
  if (s === 'FAILED') return 'text-red-600 bg-red-50 border-red-200'
  if (s === 'LOCKED') return 'text-orange-600 bg-orange-50 border-orange-200'
  return 'text-amber-700 bg-amber-50 border-amber-200'
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-bold tracking-wide', statusCls(status))}>
      {status.toUpperCase()}
    </span>
  )
}

function stepIcon(status: string, index: number) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return { symbol: '✓', cls: 'bg-emerald-100 text-emerald-700' }
  if (s === 'FAILED' || s === 'LOCKED') return { symbol: '✗', cls: 'bg-red-100 text-red-600' }
  return { symbol: String(index + 1), cls: 'bg-amber-100 text-amber-700' }
}

function SessionCard({ rec }: { rec: SessionRecord }) {
  const [expanded, setExpanded] = useState(false)
  const time = new Date(rec.startedAt).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })

  return (
    <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
      <div
        className="px-5 py-4 flex items-center gap-4 cursor-pointer hover:bg-slate-50/80 transition-colors"
        onClick={() => setExpanded(e => !e)}
      >
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2.5 flex-wrap">
            <span className="font-bold text-slate-900">{rec.brandId}</span>
            <span className="text-slate-300">›</span>
            <span className="text-sm text-slate-600 font-medium">{rec.targetLevel}</span>
            <StatusBadge status={rec.finalStatus} />
          </div>
          <div className="text-xs text-slate-400 mt-1 flex items-center gap-2">
            <span className="font-mono">{rec.callerId}</span>
            <span>·</span>
            <span>{rec.steps.length} step{rec.steps.length !== 1 ? 's' : ''}</span>
            <span>·</span>
            <span>{time}</span>
            <span>·</span>
            <span className="font-mono text-[10px] text-slate-300">{rec.id.slice(0, 8)}…</span>
          </div>
        </div>
        <button className="text-slate-400 hover:text-slate-600 transition-colors shrink-0 p-1">
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
      </div>

      {expanded && (
        <div className="border-t border-slate-100 bg-slate-50/60 px-5 py-4 space-y-2.5">
          {rec.steps.map((step, i) => {
            const { symbol, cls } = stepIcon(step.status, i)
            return (
              <div key={i} className="flex items-center gap-3">
                <div className={cn('w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0', cls)}>
                  {symbol}
                </div>
                <span className="text-sm text-slate-700 flex-1 font-medium">{step.label}</span>
                <StatusBadge status={step.status} />
                <span className="text-[10px] text-slate-400 font-mono tabular-nums">
                  {new Date(step.at).toLocaleTimeString()}
                </span>
              </div>
            )
          })}

          {rec.steps.length > 0 && (
            <details className="pt-1">
              <summary className="text-xs text-slate-500 cursor-pointer hover:text-slate-700 font-medium select-none">
                View final response
              </summary>
              <pre className="mt-2 bg-slate-900 text-slate-200 rounded-lg p-3 text-xs font-mono overflow-auto max-h-52 leading-relaxed">
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
      <div className="bg-white border-b border-slate-200 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Session Log</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            {sessions.length} session{sessions.length !== 1 ? 's' : ''} recorded · {todayCount} today
          </p>
        </div>
        {sessions.length > 0 && (
          <button
            onClick={handleClear}
            className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-red-600 transition-colors font-medium"
          >
            <Trash2 size={14} />Clear History
          </button>
        )}
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-3xl mx-auto space-y-6">
          {sessions.length > 0 && (
            <input
              className="w-full max-w-xs rounded-lg border border-slate-200 bg-white px-3.5 py-2.5 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition shadow-sm"
              placeholder="Filter by brand or status…"
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
          )}

          {sessions.length === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-200 bg-white p-16 text-center">
              <div className="w-12 h-12 rounded-xl bg-slate-100 flex items-center justify-center mx-auto mb-4">
                <History size={20} className="text-slate-400" />
              </div>
              <p className="font-semibold text-slate-600">No session history yet</p>
              <p className="text-sm text-slate-400 mt-1">Test sessions from the Dashboard will appear here automatically</p>
            </div>
          ) : filtered.length === 0 ? (
            <p className="text-sm text-slate-400 text-center py-8">No sessions match your filter</p>
          ) : (
            groups.map(({ label, items }) => (
              <div key={label}>
                <div className="flex items-center gap-3 mb-3">
                  <span className="text-xs font-bold text-slate-500 uppercase tracking-widest">{label}</span>
                  <div className="flex-1 h-px bg-slate-200" />
                  <span className="text-xs text-slate-400">{items.length}</span>
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
