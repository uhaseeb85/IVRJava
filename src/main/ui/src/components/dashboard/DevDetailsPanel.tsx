import type { Dispatch, SetStateAction } from 'react'
import {
  Wrench, ChevronDown, ChevronUp, Clock, Eye, EyeOff, Layers, Check, Copy,
} from 'lucide-react'
import { cn } from '../../lib/utils'
import type { SessionRecord } from '../../lib/sessions'
import StatusBadge from '../StatusBadge'
import ProcessingLog from '../ProcessingLog'

function msToText(ms: number) {
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// Visual tone for a timeline step, derived from its status (+ soft-fail flag).
// `icon` is null for in-progress steps so the caller can fall back to the step number.
function stepTone(status: string, failed?: boolean): { row: string; badge: string; icon: string | null } {
  const s = status.toUpperCase()
  if (s === 'AUTHENTICATED') return { row: 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-100 dark:border-emerald-800', badge: 'bg-emerald-200 dark:bg-emerald-800 text-emerald-800 dark:text-emerald-200', icon: '✓' }
  if (s === 'FAILED' || s === 'REDIRECT_TO_AGENT') return { row: 'bg-red-50 dark:bg-red-900/20 border-red-100 dark:border-red-800', badge: 'bg-red-200 dark:bg-red-800 text-red-700 dark:text-red-200', icon: '✗' }
  if (failed) return { row: 'bg-amber-50 dark:bg-amber-900/20 border-amber-100 dark:border-amber-800', badge: 'bg-amber-200 dark:bg-amber-800 text-amber-800 dark:text-amber-200', icon: '✗' }
  return { row: 'bg-slate-50 dark:bg-slate-700/50 border-slate-100 dark:border-slate-600', badge: 'bg-slate-200 dark:bg-slate-600 text-slate-600 dark:text-slate-300', icon: null }
}

// Summary view of the response: one row per populated field of interest.
function ResponseSummary({ response }: { response: Record<string, unknown> }) {
  return (
    <div className="space-y-1.5">
      {['status', 'currentLevel', 'targetLevel', 'nextRequiredToken', 'prompt', 'phase', 'matchedPartyId', 'remainingAttempts', 'acceptedTokens'].map(k => {
        const v = response[k]
        if (v == null || v === '' || (Array.isArray(v) && v.length === 0)) return null
        return (
          <div key={k} className="flex items-baseline gap-2 text-xs">
            <span className="text-slate-400 dark:text-slate-500 font-mono shrink-0 w-36">{k}</span>
            <span className="text-slate-300 dark:text-slate-600 shrink-0">→</span>
            {Array.isArray(v) ? (
              <div className="flex flex-wrap gap-1">
                {(v as string[]).map(t => (
                  <span key={t} className="bg-slate-200 dark:bg-slate-600 text-slate-600 dark:text-slate-300 rounded px-1.5 py-0.5 text-[10px] font-mono">{t}</span>
                ))}
              </div>
            ) : (
              <span className="text-slate-800 dark:text-slate-200 font-mono">{String(v)}</span>
            )}
          </div>
        )
      })}
    </div>
  )
}

// Stage 3 — collapsible developer panel: call timeline, raw request, response
// (summary or raw JSON), and the engine's processing log.
export default function DevDetailsPanel({
  session, response, lastRequest, lastTiming, procLog,
  showDev, setShowDev, showRequest, setShowRequest, showRaw, setShowRaw,
  showProcLog, setShowProcLog, copied, onCopyJSON,
}: {
  session: SessionRecord | null
  response: Record<string, unknown> | null
  lastRequest: Record<string, unknown> | null
  lastTiming: number | null
  procLog?: Array<{ level: string; message: string }>
  showDev: boolean
  setShowDev: Dispatch<SetStateAction<boolean>>
  showRequest: boolean
  setShowRequest: Dispatch<SetStateAction<boolean>>
  showRaw: boolean
  setShowRaw: Dispatch<SetStateAction<boolean>>
  showProcLog: boolean
  setShowProcLog: Dispatch<SetStateAction<boolean>>
  copied: boolean
  onCopyJSON: () => void
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
      <button
        onClick={() => setShowDev(v => !v)}
        className="w-full px-5 py-3 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-750 transition-colors"
      >
        <div className="flex items-center gap-2 text-sm font-bold text-slate-700 dark:text-slate-300">
          <Wrench size={13} className="text-slate-400 dark:text-slate-500" />
          Developer details
          <span className="text-[10px] font-normal text-slate-400 dark:text-slate-500 ml-1">timeline · request · response · log</span>
        </div>
        {showDev ? <ChevronUp size={14} className="text-slate-400" /> : <ChevronDown size={14} className="text-slate-400" />}
      </button>

      {showDev && (
        <div className="border-t border-slate-100 dark:border-slate-700 p-5 space-y-4">
          {/* Auth Flow timeline */}
          {session && session.steps.length > 0 && (
            <div>
              <h3 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">Call timeline</h3>
              <div className="space-y-2">
                {session.steps.map((step, i) => {
                  const s = step.status.toUpperCase()
                  const tone = stepTone(s, step.failed)
                  return (
                    <div key={i} className={cn(
                      'flex items-center gap-3 rounded-lg px-3 py-2.5 border', tone.row
                    )}>
                      <div className={cn(
                        'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold shrink-0', tone.badge
                      )}>
                        {tone.icon ?? i + 1}
                      </div>
                      <span className={cn(
                        'flex-1 text-sm font-semibold',
                        step.failed ? 'text-amber-800 dark:text-amber-300' : 'text-slate-700 dark:text-slate-300'
                      )}>{step.label}</span>
                      {step.failed && step.remainingAttempts != null && (
                        <span className={cn(
                          'shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-black tabular-nums',
                          step.remainingAttempts <= 1
                            ? 'bg-red-100 dark:bg-red-900/40 border-red-200 dark:border-red-700 text-red-700 dark:text-red-300'
                            : 'bg-amber-100 dark:bg-amber-900/40 border-amber-200 dark:border-amber-700 text-amber-700 dark:text-amber-300'
                        )}>
                          {step.remainingAttempts} left
                        </span>
                      )}
                      <StatusBadge status={s} />
                      {step.timing != null && (
                        <span className="flex items-center gap-1 text-[10px] text-slate-400 dark:text-slate-500 font-mono">
                          <Clock size={10} />{msToText(step.timing)}
                        </span>
                      )}
                      <span className="text-[10px] text-slate-400 dark:text-slate-500 font-mono tabular-nums">
                        {new Date(step.at).toLocaleTimeString()}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Request preview */}
          {lastRequest && (
            <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden">
              <button
                onClick={() => setShowRequest(r => !r)}
                className="w-full px-4 py-2.5 flex items-center justify-between hover:bg-slate-50 dark:hover:bg-slate-750 transition-colors"
              >
                <div className="flex items-center gap-2 text-xs font-bold text-slate-700 dark:text-slate-300">
                  <Eye size={12} />
                  Request sent
                  {lastTiming != null && (
                    <span className="text-[10px] font-normal text-slate-400 dark:text-slate-500 ml-1">({msToText(lastTiming)})</span>
                  )}
                </div>
                {showRequest ? <ChevronUp size={13} className="text-slate-400" /> : <ChevronDown size={13} className="text-slate-400" />}
              </button>
              {showRequest && (
                <pre className="bg-slate-50 dark:bg-slate-700/50 border-t border-slate-100 dark:border-slate-700 text-slate-700 dark:text-slate-300 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
                  {JSON.stringify(lastRequest, null, 2)}
                </pre>
              )}
            </div>
          )}

          {/* Response */}
          {response && (
            <div className="rounded-lg border border-slate-200 dark:border-slate-700 p-4">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider">Response</h3>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setShowRaw(r => !r)}
                    className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 transition-colors font-semibold"
                  >
                    {showRaw ? <EyeOff size={12} /> : <Layers size={12} />}
                    {showRaw ? 'Raw JSON' : 'Summary'}
                  </button>
                  <button
                    onClick={onCopyJSON}
                    className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 transition-colors font-semibold"
                  >
                    {copied ? <Check size={12} className="text-emerald-500" /> : <Copy size={12} />}
                    {copied ? 'Copied!' : 'Copy JSON'}
                  </button>
                </div>
              </div>
              {showRaw ? (
                <pre className="bg-slate-50 dark:bg-slate-700/50 text-slate-700 dark:text-slate-300 border border-slate-100 dark:border-slate-700 rounded-lg p-4 text-xs font-mono overflow-auto max-h-72 leading-relaxed">
                  {JSON.stringify(response, null, 2)}
                </pre>
              ) : (
                <div className="bg-slate-50 dark:bg-slate-700/50 border border-slate-100 dark:border-slate-700 rounded-lg p-4 overflow-auto max-h-72">
                  <ResponseSummary response={response} />
                </div>
              )}
            </div>
          )}

          {/* Processing Log */}
          {procLog && procLog.length > 0 && (
            <ProcessingLog
              log={procLog}
              open={showProcLog}
              onToggle={() => setShowProcLog(v => !v)}
            />
          )}
        </div>
      )}
    </div>
  )
}
