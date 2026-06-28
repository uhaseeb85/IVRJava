import { Activity, ChevronDown, ChevronUp } from 'lucide-react'
import { cn } from '../lib/utils'

// Badge + text colors for a processing-log entry, keyed by its level.
function procLogTone(level: string): { badge: string; text: string } {
  const l = level.toUpperCase()
  if (l === 'PASS') return { badge: 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300', text: 'text-emerald-800 dark:text-emerald-300' }
  if (l === 'FAIL') return { badge: 'bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-400',     text: 'text-red-700 dark:text-red-300' }
  if (l === 'WARN') return { badge: 'bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300', text: 'text-amber-800 dark:text-amber-300' }
  return              { badge: 'bg-slate-100 dark:bg-slate-600 text-slate-500 dark:text-slate-300',  text: 'text-slate-600 dark:text-slate-400' }
}

// Collapsible per-request processing log emitted by the auth engine.
export default function ProcessingLog({ log, open, onToggle }: {
  log: Array<{ level: string; message: string }>
  open: boolean
  onToggle: () => void
}) {
  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden">
      <button
        onClick={onToggle}
        className="w-full px-4 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-750 transition-colors"
      >
        <div className="flex items-center gap-2 text-xs font-bold text-slate-700 dark:text-slate-300">
          <Activity size={12} className="text-indigo-500" />
          Processing Log
          <span className="text-[10px] font-normal text-slate-400 dark:text-slate-500 ml-1">
            ({log.length} event{log.length !== 1 ? 's' : ''})
          </span>
        </div>
        {open ? <ChevronUp size={13} className="text-slate-400" /> : <ChevronDown size={13} className="text-slate-400" />}
      </button>
      {open && (
        <div className="border-t border-slate-100 dark:border-slate-700 divide-y divide-slate-50 dark:divide-slate-700/50">
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
