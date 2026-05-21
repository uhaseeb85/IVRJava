import { useState, useEffect } from 'react'
import { cn } from '../lib/utils'
import { ArrowLeft, Save, Settings2, Terminal, GitBranch, Lock, Plus, Trash2, Users } from 'lucide-react'

const LEVELS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const
const TYPES  = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4'] as const
type TokenType = typeof TYPES[number]
type AuthLevel = typeof LEVELS[number]

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
  levelRules: Record<string, LevelRule>
  disambiguation?: {
    maxDisambiguationTokens?: number
    rules?: { type: string }[]
  }
}

const LEVEL_COLOR: Record<string, string> = {
  BASIC:    'text-slate-700 bg-slate-100 border-slate-300',
  STANDARD: 'text-blue-700 bg-blue-50 border-blue-200',
  ELEVATED: 'text-violet-700 bg-violet-50 border-violet-200',
  ADMIN:    'text-orange-700 bg-orange-50 border-orange-200',
}

const LEVEL_LEFT: Record<string, string> = {
  BASIC:    'border-l-slate-400',
  STANDARD: 'border-l-blue-500',
  ELEVATED: 'border-l-violet-500',
  ADMIN:    'border-l-orange-500',
}

const API = {
  get: (id: string): Promise<BrandConfig> => fetch('/api/brands/' + id).then(r => r.json()),
  save: (cfg: BrandConfig) => {
    const isNew = !cfg._existing
    return fetch(isNew ? '/api/brands' : '/api/brands/' + cfg.brandId, {
      method: isNew ? 'POST' : 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(cfg),
    }).then(r => { if (!r.ok) throw new Error('Save failed'); return r.json() })
  },
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

// ── Main component ─────────────────────────────────────────────────────────────

export default function BrandEditor({ brand, onBack }: { brand: { brandId: string }; onBack: () => void }) {
  const isNew = !brand.brandId
  const [cfg, setCfg] = useState<BrandConfig>({
    brandId: brand.brandId,
    levelRules: {},
    disambiguation: { maxDisambiguationTokens: 3, rules: [] },
  })
  const [tab, setTab] = useState<'rules' | 'disambiguation' | 'flow' | 'json'>('rules')
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(!isNew)
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null)

  useEffect(() => {
    if (isNew) return
    API.get(brand.brandId)
      .then(c => {
        c._existing = true
        if (!c.disambiguation) c.disambiguation = { maxDisambiguationTokens: 3, rules: [] }
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

  const addLevel = (lvl: AuthLevel) => {
    setCfg(c => ({ ...c, levelRules: { ...c.levelRules, [lvl]: freshRule() } }))
  }

  const removeLevel = (lvl: string) => {
    setCfg(c => {
      const next = { ...c.levelRules }
      delete next[lvl]
      return { ...c, levelRules: next }
    })
  }

  const setLevelField = (lvl: string, field: 'maxRetriesPerToken' | 'lockoutSeconds', val: number) => {
    setCfg(c => ({
      ...c,
      levelRules: { ...c.levelRules, [lvl]: { ...c.levelRules[lvl], [field]: val } },
    }))
  }

  const addPath = (lvl: string) => {
    setCfg(c => {
      const paths = c.levelRules[lvl].paths
      return {
        ...c,
        levelRules: {
          ...c.levelRules,
          [lvl]: {
            ...c.levelRules[lvl],
            paths: [...paths, { pathIndex: paths.length, description: '', requiredTokens: [], backupTokens: null }],
          },
        },
      }
    })
  }

  const removePath = (lvl: string, pi: number) => {
    setCfg(c => ({
      ...c,
      levelRules: {
        ...c.levelRules,
        [lvl]: {
          ...c.levelRules[lvl],
          paths: c.levelRules[lvl].paths.filter((_, i) => i !== pi),
        },
      },
    }))
  }

  const setPathDesc = (lvl: string, pi: number, desc: string) => {
    setCfg(c => {
      const paths = c.levelRules[lvl].paths.map((p, i) => i === pi ? { ...p, description: desc } : p)
      return { ...c, levelRules: { ...c.levelRules, [lvl]: { ...c.levelRules[lvl], paths } } }
    })
  }

  const toggleToken = (lvl: string, pi: number, tok: TokenType) => {
    setCfg(c => {
      const paths = c.levelRules[lvl].paths.map((p, i) => {
        if (i !== pi) return p
        const has = p.requiredTokens.includes(tok)
        const requiredTokens = has ? p.requiredTokens.filter(t => t !== tok) : [...p.requiredTokens, tok]
        const backupTokens = has && p.backupTokens
          ? (() => {
              const bt = { ...p.backupTokens }
              delete bt[tok]
              return Object.keys(bt).length ? bt : null
            })()
          : p.backupTokens
        return { ...p, requiredTokens, backupTokens }
      })
      return { ...c, levelRules: { ...c.levelRules, [lvl]: { ...c.levelRules[lvl], paths } } }
    })
  }

  const toggleBackup = (lvl: string, pi: number, req: TokenType, alt: TokenType) => {
    setCfg(c => {
      const paths = c.levelRules[lvl].paths.map((p, i) => {
        if (i !== pi) return p
        const bt: Record<string, TokenType[]> = p.backupTokens ? { ...p.backupTokens } : {}
        const list = bt[req] ? [...bt[req]] : []
        const idx = list.indexOf(alt)
        if (idx >= 0) list.splice(idx, 1); else list.push(alt)
        if (list.length) bt[req] = list; else delete bt[req]
        return { ...p, backupTokens: Object.keys(bt).length ? bt : null }
      })
      return { ...c, levelRules: { ...c.levelRules, [lvl]: { ...c.levelRules[lvl], paths } } }
    })
  }

  const toggleDisambigRule = (rule: string) => {
    setCfg(c => {
      const rules = c.disambiguation?.rules ?? []
      const has = rules.some(r => r.type === rule)
      return {
        ...c,
        disambiguation: {
          ...c.disambiguation,
          rules: has ? rules.filter(r => r.type !== rule) : [...rules, { type: rule }],
        },
      }
    })
  }

  const save = async () => {
    if (!cfg.brandId.trim()) { showToast('Brand ID is required', false); return }
    setSaving(true)
    try {
      await API.save(cfg)
      setCfg(c => ({ ...c, _existing: true }))
      showToast(`Brand "${cfg.brandId}" saved`, true)
    } catch {
      showToast('Save failed — check the console for details', false)
    } finally {
      setSaving(false)
    }
  }

  // ── Styles ───────────────────────────────────────────────────────────────────

  const inputCls = 'w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition disabled:bg-slate-50 disabled:cursor-not-allowed'

  const tokenChip = (active: boolean) => cn(
    'inline-flex items-center rounded-lg px-3 py-1.5 text-xs font-bold cursor-pointer transition-colors select-none border',
    active
      ? 'bg-indigo-600 text-white border-indigo-600 shadow-sm'
      : 'bg-white text-slate-500 border-slate-200 hover:border-indigo-300 hover:text-indigo-600'
  )

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
          <button onClick={() => setTab('disambiguation')} className={tabBtn('disambiguation')}>
            <Users size={14} />Disambiguation
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
              </div>

              {/* Levels */}
              {LEVELS.map(lvl => {
                const rule = cfg.levelRules[lvl]
                return (
                  <div key={lvl} className={cn(
                    'bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden',
                    rule ? `border-l-4 ${LEVEL_LEFT[lvl]}` : ''
                  )}>
                    <div className="px-5 py-4 flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <span className={cn('inline-flex rounded-full border px-2.5 py-0.5 text-xs font-bold', LEVEL_COLOR[lvl])}>
                          {lvl}
                        </span>
                        {rule && (
                          <span className="text-[10px] font-bold text-emerald-600 bg-emerald-50 px-2 py-0.5 rounded-full border border-emerald-200 uppercase tracking-wide">
                            active
                          </span>
                        )}
                      </div>
                      {rule
                        ? <button onClick={() => removeLevel(lvl)} className="text-xs text-slate-400 hover:text-red-600 transition-colors font-semibold">Remove</button>
                        : <button onClick={() => addLevel(lvl)} className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors font-semibold">+ Add level</button>
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
                              onChange={e => setLevelField(lvl, 'maxRetriesPerToken', parseInt(e.target.value) || 1)}
                            />
                          </div>
                          <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1.5">Lockout Duration (sec)</label>
                            <input
                              type="number" min={0}
                              className={inputCls}
                              value={rule.lockoutSeconds}
                              onChange={e => setLevelField(lvl, 'lockoutSeconds', parseInt(e.target.value) || 0)}
                            />
                          </div>
                        </div>

                        {/* Paths */}
                        <div>
                          <div className="flex items-center justify-between mb-3">
                            <span className="text-xs font-bold text-slate-600 uppercase tracking-wider">Auth Paths</span>
                            <button onClick={() => addPath(lvl)} className="text-xs text-indigo-600 hover:text-indigo-800 transition-colors font-semibold flex items-center gap-1">
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
                                    <button onClick={() => removePath(lvl, pi)}
                                      className="text-slate-400 hover:text-red-600 transition-colors">
                                      <Trash2 size={13} />
                                    </button>
                                  )}
                                </div>

                                <input
                                  className={inputCls}
                                  placeholder="Description (e.g. Account + PIN)"
                                  value={p.description || ''}
                                  onChange={e => setPathDesc(lvl, pi, e.target.value)}
                                />

                                <div>
                                  <label className="block text-xs font-semibold text-slate-500 mb-2">Required Tokens</label>
                                  <div className="flex flex-wrap gap-1.5">
                                    {TYPES.map(t => (
                                      <span
                                        key={t}
                                        className={tokenChip(p.requiredTokens.includes(t))}
                                        onClick={() => toggleToken(lvl, pi, t)}
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
                                              onChange={() => toggleBackup(lvl, pi, req, t)}
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
              })}
            </>
          )}

          {/* ── Disambiguation tab ── */}
          {tab === 'disambiguation' && (
            <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-6 space-y-6">
              <div>
                <h2 className="font-bold text-slate-800">Party Disambiguation</h2>
                <p className="text-sm text-slate-500 mt-0.5">Always-on. Configure token limits and filter rules below.</p>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-500 mb-1.5">Max Disambiguation Tokens</label>
                <input
                  type="number" min={1} max={10}
                  className={cn(inputCls, 'w-28')}
                  value={cfg.disambiguation?.maxDisambiguationTokens ?? 3}
                  onChange={e => setCfg(c => ({
                    ...c,
                    disambiguation: { ...c.disambiguation, maxDisambiguationTokens: parseInt(e.target.value) || 3 },
                  }))}
                />
                <p className="text-xs text-slate-400 mt-1.5">Maximum disambiguation rounds before failing the caller</p>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-500 mb-3">Filter Rules</label>
                <div className="space-y-2.5">
                  {(['EXCLUDE_INACTIVE', 'PREFER_PRIMARY_ANI'] as const).map(rule => {
                    const active = cfg.disambiguation?.rules?.some(r => r.type === rule)
                    return (
                      <label
                        key={rule}
                        className={cn(
                          'flex items-start gap-3 p-4 rounded-xl border cursor-pointer transition-colors select-none',
                          active ? 'border-indigo-200 bg-indigo-50/40' : 'border-slate-200 hover:border-indigo-200 hover:bg-slate-50'
                        )}
                      >
                        <input
                          type="checkbox"
                          checked={!!active}
                          onChange={() => toggleDisambigRule(rule)}
                          className="mt-0.5 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
                        />
                        <div>
                          <span className="text-sm font-bold text-slate-700 block">
                            {rule === 'EXCLUDE_INACTIVE' ? 'Exclude Inactive Parties' : 'Prefer Primary ANI'}
                          </span>
                          <span className="text-xs text-slate-500">
                            {rule === 'EXCLUDE_INACTIVE'
                              ? 'Filter out parties where active is false before disambiguation'
                              : 'Narrow candidates to parties where this ANI is the primary contact number'
                            }
                          </span>
                        </div>
                      </label>
                    )
                  })}
                </div>
              </div>
            </div>
          )}

          {/* ── Flow tab ── */}
          {tab === 'flow' && <FlowVisualizer levelRules={cfg.levelRules} />}

          {/* ── JSON tab ── */}
          {tab === 'json' && (
            <div className="bg-white rounded-xl border border-slate-200 shadow-sm p-5">
              <p className="text-xs font-semibold text-slate-500 mb-3 uppercase tracking-wider">Config Preview (read-only)</p>
              <pre className="bg-slate-900 text-slate-200 rounded-lg p-4 text-xs font-mono overflow-auto max-h-[600px] leading-relaxed">
                {JSON.stringify(cfg, (key, val) => {
                  if (key === '_existing') return undefined
                  if (
                    key === 'disambiguation' && val &&
                    !val.rules?.length &&
                    val.maxDisambiguationTokens === 3
                  ) return undefined
                  return val
                }, 2)}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
