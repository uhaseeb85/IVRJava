import type { Dispatch, SetStateAction } from 'react'
import {
  Phone, PhoneCall, ShieldCheck, Check, ChevronDown, ChevronUp, Loader2,
} from 'lucide-react'
import { cn } from '../../lib/utils'
import { inputCls } from '../../lib/styles'
import { LEVEL_BLURB, SAMPLE_CALLERS, levelsForBrand } from '../../lib/ivrMeta'
import type { BrandSummary } from '../../lib/api'

// Stage 1 — "Who's calling?": brand picker, target level, caller number, and
// the advanced transfer toggle. Pure presentation; all state lives in Dashboard.
export default function CallSetupCard({
  brands, form, setForm, idOnly, brandLevels, canPlaceCall, loading,
  showAdvanced, setShowAdvanced, onPlaceCall,
}: {
  brands: BrandSummary[]
  form: Record<string, string>
  setForm: Dispatch<SetStateAction<Record<string, string>>>
  idOnly: boolean
  brandLevels: string[]
  canPlaceCall: boolean
  loading: boolean
  showAdvanced: boolean
  setShowAdvanced: Dispatch<SetStateAction<boolean>>
  onPlaceCall: () => void
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm p-6 space-y-6">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-indigo-50 dark:bg-indigo-900/50 flex items-center justify-center">
          <Phone size={18} className="text-indigo-600 dark:text-indigo-400" />
        </div>
        <div>
          <h2 className="text-base font-bold text-slate-900 dark:text-slate-100">Start a call</h2>
          <p className="text-sm text-slate-500 dark:text-slate-400">Pick who's calling and what they want to do.</p>
        </div>
      </div>

      {/* Brand picker */}
      <div>
        <p className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">1 · Which brand are they calling?</p>
        {brands.length === 0 ? (
          <p className="text-sm text-slate-400 dark:text-slate-500 italic">No brands configured yet — add one on the Brands page first.</p>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
            {brands.map(b => {
              const levels = levelsForBrand(b)
              const selected = form.brandId === b.brandId
              return (
                <button
                  key={b.brandId}
                  onClick={() => setForm(f => ({ ...f, brandId: b.brandId, targetLevel: b.identificationOnly ? 'NONE' : '' }))}
                  className={cn(
                    'text-left rounded-xl border p-3 transition-all',
                    selected
                      ? 'border-indigo-400 dark:border-indigo-500 bg-indigo-50/60 dark:bg-indigo-900/30 ring-1 ring-indigo-200 dark:ring-indigo-700'
                      : 'border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 hover:border-indigo-200 dark:hover:border-indigo-600 hover:bg-slate-50 dark:hover:bg-slate-600'
                  )}
                >
                  <div className="flex items-center gap-1.5">
                    <ShieldCheck size={14} className={selected ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400 dark:text-slate-500'} />
                    <span className="text-sm font-bold text-slate-800 dark:text-slate-200 truncate">{b.brandId}</span>
                  </div>
                  <div className="flex flex-wrap gap-1 mt-2">
                    {b.identificationOnly
                      ? <span className="bg-sky-100 dark:bg-sky-900/40 text-sky-600 dark:text-sky-400 rounded px-1.5 py-0.5 text-[10px] font-semibold">ID ONLY</span>
                      : levels.map(l => (
                          <span key={l} className="bg-slate-100 dark:bg-slate-600 text-slate-500 dark:text-slate-300 rounded px-1.5 py-0.5 text-[10px] font-semibold">{l}</span>
                        ))}
                  </div>
                </button>
              )
            })}
          </div>
        )}
      </div>

      {/* Identification-only note (replaces the level chooser) */}
      {form.brandId && idOnly && (
        <div className="rounded-xl border border-sky-200 dark:border-sky-800 bg-sky-50/60 dark:bg-sky-900/20 p-3">
          <p className="text-xs font-bold text-sky-700 dark:text-sky-400 uppercase tracking-wider mb-1">2 · Identification only</p>
          <p className="text-sm text-sky-700 dark:text-sky-300">
            This brand just identifies the caller — no auth level is granted. The call ends once a single party is resolved (access level stays NONE).
          </p>
        </div>
      )}

      {/* Level chooser */}
      {form.brandId && !idOnly && (
        <div>
          <p className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">2 · What do they need to do?</p>
          <div className="space-y-2">
            {brandLevels.map(l => {
              const selected = form.targetLevel === l
              return (
                <button
                  key={l}
                  onClick={() => setForm(f => ({ ...f, targetLevel: l }))}
                  className={cn(
                    'w-full text-left rounded-xl border p-3 flex items-center gap-3 transition-all',
                    selected
                      ? 'border-indigo-400 bg-indigo-50/60 ring-1 ring-indigo-200'
                      : 'border-slate-200 bg-white hover:border-indigo-200 hover:bg-slate-50'
                  )}
                >
                  <span className={cn(
                    'w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0',
                    selected ? 'border-indigo-500 bg-indigo-500' : 'border-slate-300'
                  )}>
                    {selected && <Check size={11} className="text-white" />}
                  </span>
                  <span className="shrink-0 text-sm font-bold text-slate-800 w-24">{l}</span>
                  <span className="text-sm text-slate-500">{LEVEL_BLURB[l]}</span>
                </button>
              )
            })}
          </div>
        </div>
      )}

      {/* Caller */}
      {form.targetLevel && (
        <div>
          <p className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">3 · Calling from</p>
          <input
            className={inputCls}
            placeholder={SAMPLE_CALLERS[0].ani}
            value={form.callerId || ''}
            onChange={e => setForm(f => ({ ...f, callerId: e.target.value }))}
          />
          <div className="flex flex-wrap items-center gap-1.5 mt-2">
            <span className="text-[11px] text-slate-400">any number works in test mode —</span>
            {SAMPLE_CALLERS.map(c => (
              <button
                key={c.ani}
                onClick={() => setForm(f => ({ ...f, callerId: c.ani }))}
                className="rounded-full border border-slate-200 bg-white px-2.5 py-0.5 text-[11px] font-medium text-slate-500 hover:border-indigo-300 hover:text-indigo-600 transition-colors"
              >
                {c.ani}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Advanced: transfer */}
      {form.targetLevel && (
        <div className="border-t border-slate-100 pt-3">
          <button
            onClick={() => setShowAdvanced(v => !v)}
            className="flex items-center gap-1.5 text-xs font-semibold text-slate-500 hover:text-slate-800 transition-colors"
          >
            {showAdvanced ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            Advanced
          </button>
          {showAdvanced && (
            <label className="flex items-center gap-2 mt-2.5 text-sm text-slate-600 cursor-pointer">
              <input
                type="checkbox"
                checked={form.transfer === 'yes'}
                onChange={e => setForm(f => ({ ...f, transfer: e.target.checked ? 'yes' : '' }))}
                className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500/30"
              />
              Simulate a transfer from a legacy system (caller already verified their account number)
            </label>
          )}
        </div>
      )}

      {/* Place call */}
      <button
        onClick={onPlaceCall}
        disabled={!canPlaceCall}
        className="w-full rounded-xl bg-indigo-600 px-4 py-3 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2 shadow-sm"
      >
        {loading ? <Loader2 size={16} className="animate-spin" /> : <PhoneCall size={16} />}
        Place call
      </button>
    </div>
  )
}
