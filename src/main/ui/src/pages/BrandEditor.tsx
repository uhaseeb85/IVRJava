import { useState, useEffect } from 'react'
import { cn } from '../lib/utils'
import { ArrowLeft, Save, Settings2, Terminal, GitBranch, Lock, Plus, Trash2 } from 'lucide-react'
import { getBrand, saveBrand } from '../lib/api'
import { editorInputCls as inputCls } from '../lib/styles'
import { LEVELS, TOKENS as TYPES } from '../lib/ivrMeta'

type TokenType = typeof TYPES[number]

interface AuthPath {
  pathIndex: number
  description: string
  requiredTokens: TokenType[]
  backupTokens: Record<string, TokenType[]> | null
}

interface LevelRule {
  paths: AuthPath[]
  maxRetriesPerToken: number
  lockoutSeconds: number
}

interface BrandConfig {
  brandId: string
  _existing?: boolean
  identificationOnly?: boolean
  levelRules: Record<string, LevelRule>
}

const LEVEL_COLOR: Record<string, string> = {
  NONE:     'text-emerald-700 bg-emerald-50 border-emerald-200',
  BASIC:    'text-slate-700 bg-slate-100 border-slate-300',
  STANDARD: 'text-blue-700 bg-blue-50 border-blue-200',
  ELEVATED: 'text-violet-700 bg-violet-50 border-violet-200',
  ADMIN:    'text-orange-700 bg-orange-50 border-orange-200',
}

const LEVEL_LEFT: Record<string, string> = {
  NONE:     'border-l-emerald-500',
  BASIC:    'border-l-slate-400',
  STANDARD: 'border-l-blue-500',
  ELEVATED: 'border-l-violet-500',
  ADMIN:    'border-l-orange-500',
}

const freshRule = (): LevelRule => ({
  paths: [{ pathIndex: 0, description: '', requiredTokens: [], backupTokens: null }],
  maxRetriesPerToken: 3,
  lockoutSeconds: 0,
})

// ── Flow visualizer ───────────────────────────────────────────────────────────

function FlowVisualizer({ levelRules }: { levelRules: Record<string, LevelRule> }) {
  const levels = Object.keys(levelRules)
  if (levels.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-200 bg-white p-14 text-center">
        <GitBranch size={24} className="mx-auto text-slate-300 mb-3" />
        <p className="text-sm font-semibold text-slate-500">No levels configured</p>
        <p className="text-xs text-slate-400 mt-1">Add levels in the Rules tab to see the auth flow</p>
      </div>
    )
  }
  return (
    <div className="space-y-5">
      {levels.map(lvl => (
        <div key={lvl} className={cn('bg-white rounded-xl border-l-4 border border-slate-200 shadow-sm p-5', LEVEL_LEFT[lvl] ?? 'border-l-slate-400')}>
          <div className="flex items-center gap-3 mb-5">
            <span className={cn('inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-bold', LEVEL_COLOR[lvl] ?? LEVEL_COLOR.BASIC)}>
              {lvl}
            </span>
            <span className="text-xs text-slate-400">
              Max {levelRules[lvl].maxRetriesPerToken} retrie{levelRules[lvl].maxRetriesPerToken !== 1 ? 's' : ''} per token
              {levelRules[lvl].lockoutSeconds > 0 && ` · ${levelRules[lvl].lockoutSeconds}s lockout`}
            </span>
          </div>

          <div className="space-y-4">
            {levelRules[lvl].paths.map((path, pi) => (
              <div key={pi} className={cn(
                'rounded-lg border p-4',
                pi === 0 ? 'border-indigo-100 bg-indigo-50/40' : 'border-slate-100 bg-slate-50/60'
              )}>
                <div className="text-[11px] font-bold text-slate-500 uppercase tracking-wider mb-3">
                  {pi === 0 ? 'Primary Path' : `Fallback Path ${pi}`}
                  {path.description && <span className="font-normal normal-case text-slate-400 ml-2">— {path.description}</span>}
                </div>
                <div className="flex items-start flex-wrap gap-2">
                  {path.requiredTokens.length === 0 ? (
                    <span className="text-xs text-slate-400 italic">No tokens — add tokens in Rules</span>
                  ) : (
                    <>
                      {path.requiredTokens.map((tok, ti) => (
                        <div key={tok} className="flex items-start gap-2">
                          <div className="bg-white rounded-lg border border-slate-200 shadow-sm px-3 py-2 text-xs font-bold text-slate-700">
                            <div>{tok}</div>
                            {path.backupTokens?.[tok] && path.backupTokens[tok].length > 0 && (
                              <div className="mt-1.5 flex flex-wrap gap-1">
                                {path.backupTokens[tok].map(b => (
                                  <span key={b} className="text-[9px] bg-amber-50 text-amber-700 border border-amber-200 rounded-md px-1.5 py-0.5 font-semibold">
                                    alt: {b}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>
                          {ti < path.requiredTokens.length - 1 && (
                            <span className="text-slate-300 text-xl leading-none mt-2 font-light select-none">→</span>
                          )}
                        </div>
                      ))}
                      <span className="text-slate-300 text-xl leading-none mt-2 font-light select-none">→</span>
                      <div className="bg-emerald-50 rounded-lg border border-emerald-200 px-3 py-2 text-xs font-bold text-emerald-700">
                        AUTHENTICATED ✓
                      </div>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

// ── Level rule editor (shared by the NONE / identification-only branch and the
//    standard auth-level branch — only the labels differ) ──────────────────────

interface LevelRuleCardLabels {
  add: string      // button shown when the level has no rule yet
  remove: string   // button shown when the level has a rule
  lockout: string  // label for the second numeric field
  paths: string    // heading above the path list
}

// All mutations a LevelRuleCard can make to its level's rule, bundled so the
// card takes one prop instead of eight.
interface LevelRuleActions {
  addLevel: (lvl: string) => void
  removeLevel: (lvl: string) => void
  setLevelField: (lvl: string, field: 'maxRetriesPerToken' | 'lockoutSeconds', val: number) => void
  addPath: (lvl: string) => void
  removePath: (lvl: string, pi: number) => void
  setPathDesc: (lvl: string, pi: number, desc: string) => void
  toggleToken: (lvl: string, pi: number, tok: TokenType) => void
  toggleBackup: (lvl: string, pi: number, req: TokenType, alt: TokenType) => void
}

const tokenChip = (active: boolean) => cn(
  'inline-flex items-center rounded-lg px-3 py-1.5 text-xs font-bold cursor-pointer transition-colors select-none border',
  active
    ? 'bg-indigo-600 text-white border-indigo-600 shadow-sm'
    : 'bg-white text-slate-500 border-slate-200 hover:border-indigo-300 hover:text-indigo-600'
)

function LevelRuleCard({ level, rule, labels, actions }: {
  level: string
  rule: LevelRule | undefined
  labels: LevelRuleCardLabels
  actions: LevelRuleActions
}) {
  return (
    <div className={cn(
      'bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden',
      rule ? `border-l-4 ${LEVEL_LEFT[level] ?? 'border-l-slate-400'}` : ''
    )}>
      <div className="px-5 py-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className={cn('inline-flex rounded-full border px-2.5 py-0.5 text-xs font-bold', LEVEL_COLOR[level] ?? LEVEL_COLOR.BASIC)}>
            {level}
          </span>
          {rule && (
            <span className="text-[10px] font-bold text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded-full border border-emerald-200 uppercase tracking-wide">
              active
            </span>
          )}
        </div>
        {rule
          ? <button onClick={() => actions.removeLevel(level)} className="text-xs text-slate-400 hover:text-red-600 transition-colors font-semibold">{labels.remove}</button>
          : <button onClick={() => actions.addLevel(level)} className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors font-semibold">{labels.add}</button>
        }
      </div>

      {rule && (
        <div className="border-t border-slate-100 px-5 pb-5 space-y-5 pt-4">
          {/* Settings row */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold text-slate-500 mb-1.5">Max Retries per Token</label>
              <input
                type="number" min={1}
                className={inputCls}
                value={rule.maxRetriesPerToken}
                onChange={e => actions.setLevelField(level, 'maxRetriesPerToken', parseInt(e.target.value) || 1)}
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-500 mb-1.5">{labels.lockout}</label>
              <input
                type="number" min={0}
                className={inputCls}
                value={rule.lockoutSeconds}
                onChange={e => actions.setLevelField(level, 'lockoutSeconds', parseInt(e.target.value) || 0)}
              />
            </div>
          </div>

          {/* Paths */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-bold text-slate-600 uppercase tracking-wider">{labels.paths}</span>
              <button onClick={() => actions.addPath(level)} className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors font-semibold flex items-center gap-1">
                <Plus size={11} />Add Path
              </button>
            </div>

            <div className="space-y-3">
              {rule.paths.map((p, pi) => (
                <div key={pi} className={cn(
                  'rounded-xl border p-4 space-y-4',
                  pi === 0 ? 'border-indigo-100 bg-indigo-50/30' : 'border-slate-100 bg-slate-50/40'
                )}>
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wide">
                      {pi === 0 ? 'Primary Path' : `Fallback ${pi}`}
                    </span>
                    {rule.paths.length > 1 && (
                      <button onClick={() => actions.removePath(level, pi)}
                        className="text-slate-400 hover:text-red-600 transition-colors">
                        <Trash2 size={13} />
                      </button>
                    )}
                  </div>

                  <input
                    className={inputCls}
                    placeholder="Description (e.g. Account + PIN)"
                    value={p.description || ''}
                    onChange={e => actions.setPathDesc(level, pi, e.target.value)}
                  />

                  <div>
                    <label className="block text-xs font-semibold text-slate-500 mb-2">Required Tokens</label>
                    <div className="flex flex-wrap gap-1.5">
                      {TYPES.map(t => (
                        <span
                          key={t}
                          className={tokenChip(p.requiredTokens.includes(t))}
                          onClick={() => actions.toggleToken(level, pi, t)}
                        >
                          {t}
                        </span>
                      ))}
                    </div>
                  </div>

                  {p.requiredTokens.map(req => (
                    <div key={req} className="border-t border-slate-100 pt-3">
                      <label className="block text-xs font-semibold text-slate-500 mb-2">
                        Backup tokens for <span className="text-slate-700">{req}</span>
                      </label>
                      <div className="flex flex-wrap gap-x-4 gap-y-2">
                        {TYPES.filter(t => t !== req).map(t => {
                          const checked = !!(p.backupTokens?.[req]?.includes(t))
                          return (
                            <label key={t} className="flex items-center gap-2 text-xs text-slate-500 cursor-pointer hover:text-slate-800 select-none">
                              <input
                                type="checkbox"
                                checked={checked}
                                onChange={() => actions.toggleBackup(level, pi, req, t)}
                                className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                              />
                              {t}
                            </label>
                          )
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Main component ─────────────────────────────────────────────────────────────

export default function BrandEditor({ brand, onBack }: { brand: { brandId: string }; onBack: () => void }) {
  const isNew = !brand.brandId
  const [cfg, setCfg] = useState<BrandConfig>({
    brandId: brand.brandId,
    levelRules: {},
  })
  const [tab, setTab] = useState<'rules' | 'flow' | 'json'>('rules')
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(!isNew)
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null)

  useEffect(() => {
    if (isNew) return
    getBrand<BrandConfig>(brand.brandId)
      .then(c => {
        c._existing = true
        setCfg(c)
      })
      .catch(() => setToast({ msg: 'Failed to load brand config', ok: false }))
      .finally(() => setLoading(false))
  }, [brand.brandId, isNew])

  const showToast = (msg: string, ok: boolean) => {
    setToast({ msg, ok })
    setTimeout(() => setToast(null), 3000)
  }

  // ── State helpers ────────────────────────────────────────────────────────────
  // All rule mutations funnel through updateRule/updatePath, which own the
  // immutable-spread plumbing so each action below stays a one-liner.

  const updateRule = (lvl: string, fn: (r: LevelRule) => LevelRule) =>
    setCfg(c => ({ ...c, levelRules: { ...c.levelRules, [lvl]: fn(c.levelRules[lvl]) } }))

  const updatePath = (lvl: string, pi: number, fn: (p: AuthPath) => AuthPath) =>
    updateRule(lvl, r => ({ ...r, paths: r.paths.map((p, i) => (i === pi ? fn(p) : p)) }))

  const actions: LevelRuleActions = {
    addLevel: lvl => updateRule(lvl, () => freshRule()),

    removeLevel: lvl => setCfg(c => {
      const next = { ...c.levelRules }
      delete next[lvl]
      return { ...c, levelRules: next }
    }),

    setLevelField: (lvl, field, val) => updateRule(lvl, r => ({ ...r, [field]: val })),

    addPath: lvl => updateRule(lvl, r => ({
      ...r,
      paths: [...r.paths, { pathIndex: r.paths.length, description: '', requiredTokens: [], backupTokens: null }],
    })),

    removePath: (lvl, pi) => updateRule(lvl, r => ({ ...r, paths: r.paths.filter((_, i) => i !== pi) })),

    setPathDesc: (lvl, pi, desc) => updatePath(lvl, pi, p => ({ ...p, description: desc })),

    toggleToken: (lvl, pi, tok) => updatePath(lvl, pi, p => {
      const has = p.requiredTokens.includes(tok)
      const requiredTokens = has ? p.requiredTokens.filter(t => t !== tok) : [...p.requiredTokens, tok]
      let backupTokens = p.backupTokens
      if (has && backupTokens) {
        // Removing a required token also drops its backup mapping.
        const bt = { ...backupTokens }
        delete bt[tok]
        backupTokens = Object.keys(bt).length ? bt : null
      }
      return { ...p, requiredTokens, backupTokens }
    }),

    toggleBackup: (lvl, pi, req, alt) => updatePath(lvl, pi, p => {
      const bt: Record<string, TokenType[]> = p.backupTokens ? { ...p.backupTokens } : {}
      const list = bt[req] ? [...bt[req]] : []
      const idx = list.indexOf(alt)
      if (idx >= 0) list.splice(idx, 1); else list.push(alt)
      if (list.length) bt[req] = list; else delete bt[req]
      return { ...p, backupTokens: Object.keys(bt).length ? bt : null }
    }),
  }

  const save = async () => {
    if (!cfg.brandId.trim()) { showToast('Brand ID is required', false); return }
    setSaving(true)
    try {
      await saveBrand(cfg)
      setCfg(c => ({ ...c, _existing: true }))
      showToast(`Brand "${cfg.brandId}" saved`, true)
    } catch {
      showToast('Save failed — check the console for details', false)
    } finally {
      setSaving(false)
    }
  }

  // ── Styles ───────────────────────────────────────────────────────────────────

  const tabBtn = (t: string) => cn(
    'flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-semibold transition-colors',
    tab === t
      ? 'bg-white text-slate-900 shadow-sm border border-slate-200'
      : 'text-slate-500 hover:text-slate-700'
  )

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-sm text-slate-400 animate-pulse">Loading brand config…</div>
      </div>
    )
  }

  return (
    <div className="flex flex-col min-h-full relative">
      {/* Toast */}
      {toast && (
        <div className={cn(
          'fixed bottom-6 right-6 rounded-xl px-5 py-3 text-sm font-semibold shadow-xl z-50 transition-all',
          toast.ok ? 'bg-slate-900 text-white' : 'bg-red-600 text-white'
        )}>
          {toast.msg}
        </div>
      )}

      {/* Header */}
      <div className="bg-white border-b border-slate-200 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div className="flex items-center gap-3 min-w-0">
          <button
            onClick={onBack}
            className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-900 transition-colors font-medium shrink-0"
          >
            <ArrowLeft size={15} />Brands
          </button>
          <span className="text-slate-300">/</span>
          <h1 className="text-xl font-bold text-slate-900 truncate">
            {cfg.brandId || 'New Brand'}
          </h1>
          {cfg._existing && (
            <span className="flex items-center gap-1 text-xs text-slate-400 shrink-0">
              <Lock size={11} />existing
            </span>
          )}
        </div>
        <button
          onClick={save}
          disabled={saving}
          className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 transition-colors shadow-sm shrink-0"
        >
          <Save size={15} />{saving ? 'Saving…' : 'Save Brand'}
        </button>
      </div>

      {/* Tabs */}
      <div className="bg-white border-b border-slate-200 px-8 py-2">
        <div className="flex gap-1 bg-slate-100 rounded-xl p-1 w-fit">
          <button onClick={() => setTab('rules')} className={tabBtn('rules')}>
            <Settings2 size={14} />Rules
          </button>
          <button onClick={() => setTab('flow')} className={tabBtn('flow')}>
            <GitBranch size={14} />Flow
          </button>
          <button onClick={() => setTab('json')} className={tabBtn('json')}>
            <Terminal size={14} />JSON
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-4xl mx-auto space-y-6">

          {/* ── Rules tab ── */}
          {tab === 'rules' && (
            <>
              {/* Brand ID */}
              <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5 space-y-3">
                <label className="text-sm font-bold text-slate-700">Brand ID</label>
                <input
                  className={inputCls}
                  value={cfg.brandId}
                  onChange={e => setCfg(c => ({ ...c, brandId: e.target.value }))}
                  placeholder="e.g. BRAND_C"
                  disabled={!!cfg._existing}
                />
                {cfg._existing && (
                  <p className="text-xs text-slate-400 flex items-center gap-1">
                    <Lock size={10} />Brand ID is locked for existing brands
                  </p>
                )}

                <label className="flex items-start gap-2.5 pt-2 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={!!cfg.identificationOnly}
                    onChange={e => setCfg(c => ({ ...c, identificationOnly: e.target.checked }))}
                    className="mt-0.5 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                  />
                  <span className="text-sm">
                    <span className="font-semibold text-slate-700">Identification only</span>
                    <span className="block text-xs text-slate-400">
                      No authentication levels — resolve caller to a single party. Optionally collect identity tokens under NONE level rules to confirm identity.
                    </span>
                  </span>
                </label>
              </div>

              {cfg.identificationOnly ? (
                <>
                <div className="bg-emerald-50 rounded-xl border border-emerald-200 p-4">
                  <p className="text-sm text-emerald-800">
                    <span className="font-semibold">Identification-only mode.</span> Optionally define tokens
                    to collect under <span className="font-semibold">NONE</span> level — these are matched against
                    the resolved party's fields to confirm caller identity. Without rules, the flow finalizes
                    immediately after party resolution.
                  </p>
                </div>

                {/* NONE level editor for identification-only brands */}
                <LevelRuleCard
                  level="NONE"
                  rule={cfg.levelRules['NONE']}
                  labels={{
                    add: '+ Add Identity Tokens',
                    remove: 'Remove Rules',
                    lockout: 'Lockout (sec)',
                    paths: 'Identity Paths',
                  }}
                  actions={actions}
                />
                </>
              ) : (
              <>
              {/* Levels */}
              {LEVELS.map(lvl => (
                <LevelRuleCard
                  key={lvl}
                  level={lvl}
                  rule={cfg.levelRules[lvl]}
                  labels={{
                    add: '+ Add level',
                    remove: 'Remove',
                    lockout: 'Redirect to Agent after (sec)',
                    paths: 'Auth Paths',
                  }}
                  actions={actions}
                />
              ))}
              </>
              )}
            </>
          )}

          {/* ── Flow tab ── */}
          {tab === 'flow' && <FlowVisualizer levelRules={cfg.levelRules} />}

          {/* ── JSON tab ── */}
          {tab === 'json' && (
            <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
              <p className="text-xs font-semibold text-slate-500 mb-3 uppercase tracking-wider">Config Preview (read-only)</p>
              <pre className="bg-slate-900 text-slate-200 rounded-lg p-4 text-xs font-mono overflow-auto max-h-[600px] leading-relaxed">
                {JSON.stringify(cfg, (key, val) => key === '_existing' ? undefined : val, 2)}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
