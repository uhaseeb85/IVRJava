import { cn } from '../lib/utils'

// Single source of truth for session-status badge colors, shared by the
// Dashboard and the Session Log.
function statusStyle(status: string) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return 'text-emerald-700 dark:text-emerald-300 bg-emerald-50 dark:bg-emerald-900/30 border-emerald-200 dark:border-emerald-700'
  if (s === 'FAILED') return 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/30 border-red-200 dark:border-red-700'
  if (s === 'REDIRECT_TO_AGENT') return 'text-orange-600 dark:text-orange-400 bg-orange-50 dark:bg-orange-900/30 border-orange-200 dark:border-orange-700'
  if (s === 'COLLECTING') return 'text-amber-700 dark:text-amber-300 bg-amber-50 dark:bg-amber-900/30 border-amber-200 dark:border-amber-700'
  return 'text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-slate-700 border-slate-200 dark:border-slate-600'
}

export default function StatusBadge({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-bold tracking-wide', statusStyle(status))}>
      {status.toUpperCase()}
    </span>
  )
}
