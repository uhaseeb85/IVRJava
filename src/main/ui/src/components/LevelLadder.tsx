import { Check } from 'lucide-react'
import { cn } from '../lib/utils'
import { LEVEL_ORDER } from '../lib/ivrMeta'

// ── Progress ladder: NONE → BASIC → … → ADMIN ──
export default function LevelLadder({ current, target }: { current?: string; target?: string }) {
  const cur = (current || 'NONE').toUpperCase()
  const tgt = (target || '').toUpperCase()
  const curIdx = LEVEL_ORDER.indexOf(cur as typeof LEVEL_ORDER[number])
  const tgtIdx = LEVEL_ORDER.indexOf(tgt as typeof LEVEL_ORDER[number])
  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      {LEVEL_ORDER.map((lvl, i) => {
        const reached = curIdx >= 0 && i <= curIdx
        const isTarget = i === tgtIdx
        return (
          <div key={lvl} className="flex items-center gap-1.5">
            {i > 0 && (
              <span className={cn('h-px w-4', reached ? 'bg-emerald-300 dark:bg-emerald-600' : 'bg-slate-200 dark:bg-slate-600')} />
            )}
            <span className={cn(
              'inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-bold transition-colors',
              reached
                ? 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-200 dark:border-emerald-700 text-emerald-700 dark:text-emerald-300'
                : isTarget
                  ? 'bg-indigo-50 dark:bg-indigo-900/30 border-indigo-200 dark:border-indigo-700 text-indigo-700 dark:text-indigo-300 ring-1 ring-indigo-200 dark:ring-indigo-700'
                  : 'bg-slate-50 dark:bg-slate-700 border-slate-200 dark:border-slate-600 text-slate-400 dark:text-slate-500'
            )}>
              {reached && <Check size={11} />}
              {lvl}
              {isTarget && !reached && <span className="text-[9px] font-semibold opacity-70">goal</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}
