import { cn } from '../lib/utils'

// Single source of truth for session-status badge colors, shared by the
// Dashboard and the Session Log.
function statusStyle(status: string) {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return 'text-emerald-700 bg-emerald-50 border-emerald-200'
  if (s === 'FAILED') return 'text-red-600 bg-red-50 border-red-200'
  if (s === 'REDIRECT_TO_AGENT') return 'text-orange-600 bg-orange-50 border-orange-200'
  if (s === 'COLLECTING') return 'text-amber-700 bg-amber-50 border-amber-200'
  return 'text-slate-500 bg-slate-100 border-slate-200'
}

export default function StatusBadge({ status }: { status: string }) {
  return (
    <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-bold tracking-wide', statusStyle(status))}>
      {status.toUpperCase()}
    </span>
  )
}
