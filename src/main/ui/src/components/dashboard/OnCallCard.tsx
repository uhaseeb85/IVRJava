import type { Dispatch, SetStateAction } from 'react'
import {
  Send, Loader2, Volume2, Lock, CheckCircle2, RotateCcw, ArrowUpRight,
} from 'lucide-react'
import { cn } from '../../lib/utils'
import { inputCls } from '../../lib/styles'
import { TOKEN_DEFAULTS, tokenLabel } from '../../lib/ivrMeta'
import type { SessionRecord } from '../../lib/sessions'
import LevelLadder from '../LevelLadder'

// Stage 2 — "On the call": progress ladder, the spoken prompt, token entry with
// backup alternatives and retry warning, and the three terminal states
// (identified / authenticated / redirected-or-failed).
export default function OnCallCard({
  session, response, form, setForm, loading, idOnly,
  currentStatus, currentLevel, sessionActive, identified, higherLevels,
  activeToken, alternatives, onSubmitToken, onEscalate, onReset,
}: {
  session: SessionRecord
  response: Record<string, unknown> | null
  form: Record<string, string>
  setForm: Dispatch<SetStateAction<Record<string, string>>>
  loading: boolean
  idOnly: boolean
  currentStatus: string
  currentLevel: string
  sessionActive: boolean
  identified: boolean
  higherLevels: string[]
  activeToken: string
  alternatives: string[]
  onSubmitToken: () => void
  onEscalate: (level: string) => void
  onReset: () => void
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm p-6 space-y-5">
      {/* Progress ladder (authentication brands only) */}
      {!idOnly && (
        <div className="flex items-center justify-between gap-4">
          <LevelLadder current={currentLevel} target={session.targetLevel} />
        </div>
      )}

      {/* Spoken prompt bubble */}
      {response?.prompt ? (
        <div className="flex items-start gap-3 rounded-xl bg-slate-50 dark:bg-slate-700/50 border border-slate-100 dark:border-slate-600 p-4">
          <div className="w-8 h-8 rounded-full bg-indigo-100 dark:bg-indigo-900/50 flex items-center justify-center shrink-0">
            <Volume2 size={15} className="text-indigo-600 dark:text-indigo-400" />
          </div>
          <div className="flex-1">
            <p className="text-[10px] font-bold uppercase tracking-widest text-slate-400 dark:text-slate-500 mb-0.5">The system says</p>
            <p className="text-sm text-slate-800 dark:text-slate-200 font-medium leading-relaxed">{String(response.prompt)}</p>
          </div>
        </div>
      ) : null}

      {/* Active: token entry */}
      {sessionActive && (
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
              Enter the caller's <span className="text-slate-800 dark:text-slate-200">{tokenLabel(activeToken)}</span>
            </label>
            <div className="flex gap-2">
              <input
                autoFocus
                className={inputCls}
                placeholder={TOKEN_DEFAULTS[activeToken] ?? ''}
                value={form.tokenValue || ''}
                onChange={e => setForm(f => ({ ...f, tokenValue: e.target.value }))}
                onKeyDown={e => e.key === 'Enter' && onSubmitToken()}
              />
              <button
                onClick={onSubmitToken}
                disabled={loading}
                className="shrink-0 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-2 shadow-sm"
              >
                {loading ? <Loader2 size={15} className="animate-spin" /> : <Send size={14} />}
                Submit
              </button>
            </div>
            <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-1.5">
              Pre-filled with a valid sample value — just press Enter. (Clear it or change it to test a failure.)
            </p>
          </div>

          {/* Backup-token alternatives */}
          {alternatives.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="text-[11px] text-slate-400 dark:text-slate-500">Can't provide that? Use instead:</span>
              {alternatives.map(alt => (
                <button
                  key={alt}
                  onClick={() => setForm(f => ({ ...f, tokenType: alt, tokenValue: TOKEN_DEFAULTS[alt] ?? '' }))}
                  className="rounded-full border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-2.5 py-0.5 text-[11px] font-medium text-slate-600 dark:text-slate-300 hover:border-indigo-300 dark:hover:border-indigo-500 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
                >
                  {tokenLabel(alt)}
                </button>
              ))}
            </div>
          )}

          {/* Inline retry warning */}
          {response?.remainingAttempts != null && (
            <div className={cn(
              'rounded-lg border px-3 py-2 text-xs font-semibold',
              (response.remainingAttempts as number) <= 1
                ? 'bg-red-50 border-red-200 text-red-600'
                : 'bg-amber-50 border-amber-200 text-amber-700'
            )}>
              {response.remainingAttempts as number} {(response.remainingAttempts as number) === 1 ? 'try' : 'tries'} left for the {tokenLabel(activeToken)}.
            </div>
          )}
        </div>
      )}

      {/* Terminal: identified (identification-only brands) */}
      {identified && (
        <div className="rounded-xl bg-sky-50 border border-sky-200 p-4">
          <div className="flex items-center gap-2">
            <CheckCircle2 size={18} className="text-sky-600" />
            <p className="text-sm font-bold text-sky-800">
              Identified{response?.matchedPartyId ? ` — party ${String(response.matchedPartyId)}` : ''}
            </p>
          </div>
          <p className="text-xs text-sky-700 mt-1">Access level: NONE (identification only).</p>
        </div>
      )}

      {/* Terminal: authenticated */}
      {currentStatus === 'AUTHENTICATED' && !identified && (
        <div className="rounded-xl bg-emerald-50 border border-emerald-200 p-4">
          <div className="flex items-center gap-2">
            <CheckCircle2 size={18} className="text-emerald-600" />
            <p className="text-sm font-bold text-emerald-800">Verified — reached {currentLevel} access</p>
          </div>
          {higherLevels.length > 0 && (
            <div className="mt-3">
              <p className="text-xs text-emerald-700 mb-1.5">Need to do more? Ask for higher access:</p>
              <div className="flex flex-wrap gap-1.5">
                {higherLevels.map(l => (
                  <button
                    key={l}
                    onClick={() => onEscalate(l)}
                    disabled={loading}
                    className="rounded-full border border-emerald-300 bg-white px-3 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-100 disabled:opacity-50 transition-colors flex items-center gap-1.5"
                  >
                    <ArrowUpRight size={12} />{l}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Terminal: redirect to agent / failed */}
      {(currentStatus === 'REDIRECT_TO_AGENT' || currentStatus === 'FAILED') && (
        <div className="rounded-xl bg-red-50 border border-red-200 p-4">
          <div className="flex items-center gap-2">
            <Lock size={17} className="text-red-600" />
            <p className="text-sm font-bold text-red-700">
              {currentStatus === 'REDIRECT_TO_AGENT' ? 'Redirect to Agent' : 'Verification failed'}
            </p>
          </div>
          {typeof response?.lockedUntil === 'string' && (
            <p className="text-xs text-red-500 mt-1">Redirecting until {new Date(response.lockedUntil).toLocaleTimeString()}</p>
          )}
          <button
            onClick={onReset}
            className="mt-3 rounded-lg bg-red-600 px-3.5 py-1.5 text-xs font-bold text-white hover:bg-red-500 transition-colors flex items-center gap-1.5"
          >
            <RotateCcw size={12} />Start a new call
          </button>
        </div>
      )}
    </div>
  )
}
