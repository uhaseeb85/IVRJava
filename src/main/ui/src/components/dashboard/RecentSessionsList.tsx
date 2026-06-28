import type { Dispatch, SetStateAction } from 'react'
import { Clock, ChevronDown, ChevronUp } from 'lucide-react'
import type { SessionRecord } from '../../lib/sessions'
import StatusBadge from '../StatusBadge'

// Collapsible list of the five most recent calls; clicking one restores it.
export default function RecentSessionsList({ sessions, show, setShow, onRestore }: {
  sessions: SessionRecord[]
  show: boolean
  setShow: Dispatch<SetStateAction<boolean>>
  onRestore: (rec: SessionRecord) => void
}) {
  return (
    <div>
      <button
        onClick={() => setShow(r => !r)}
        className="flex items-center gap-2 text-sm font-bold text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 transition-colors mb-3"
      >
        <Clock size={13} />
        Recent calls ({sessions.length})
        {show ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
      </button>
      {show && (
        <div className="space-y-2">
          {sessions.map(rec => (
            <div
              key={rec.id}
              onClick={() => onRestore(rec)}
              className="bg-white dark:bg-slate-700 rounded-lg border border-slate-200 dark:border-slate-600 px-4 py-3 flex items-center gap-4 cursor-pointer hover:border-indigo-200 dark:hover:border-indigo-500 hover:bg-indigo-50/30 dark:hover:bg-indigo-900/20 transition-colors"
            >
              <div className="flex-1 min-w-0 flex items-center gap-3">
                <span className="text-sm font-bold text-slate-900 dark:text-slate-100 shrink-0">{rec.brandId}</span>
                <span className="text-slate-300 dark:text-slate-600">›</span>
                <span className="text-sm text-slate-500 dark:text-slate-400 font-medium">{rec.targetLevel}</span>
                <StatusBadge status={rec.finalStatus} />
              </div>
              <div className="text-xs text-slate-400 dark:text-slate-500 flex items-center gap-2">
                <span>{rec.steps.length} step{rec.steps.length !== 1 ? 's' : ''}</span>
                <span>·</span>
                <span>{new Date(rec.startedAt).toLocaleTimeString()}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
