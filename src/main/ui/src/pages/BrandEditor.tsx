import { useState } from 'react'
import { cn } from '../lib/utils'

const LEVELS = ['BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const
const TYPES = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4'] as const
type TokenType = typeof TYPES[number]
type AuthLevel = typeof LEVELS[number]

interface Path {
  pathIndex: number
  description: string
  requiredTokens: TokenType[]
  backupTokens: Record<string, TokenType[]> | null
}

interface LevelRule {
  paths: Path[]
  maxRetriesPerToken: number
  lockoutSeconds: number
}

interface BrandConfig {
  brandId: string
  _existing?: boolean
  levelRules: Record<string, LevelRule>
  disambiguation?: { maxDisambiguationTokens?: number; rules?: { type: string }[] }
}

const API = {
  get: (id: string) => fetch('/api/brands/' + id).then(r => r.json()),
  save: (cfg: BrandConfig) => {
    const isNew = !cfg._existing
    const url = isNew ? '/api/brands' : '/api/brands/' + cfg.brandId
    return fetch(url, {
      method: isNew ? 'POST' : 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(cfg)
    }).then(r => { if (!r.ok) throw Error(); return r.json() })
  },
  validate: (cfg: BrandConfig) => fetch('/api/brands/validate', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cfg)
  }).then(r => r.json())
}

const freshRule = (): LevelRule => ({
  paths: [{ pathIndex: 0, description: '', requiredTokens: [], backupTokens: null }],
  maxRetriesPerToken: 3,
  lockoutSeconds: 0
})

export default function BrandEditor({ brand, onBack }: { brand: { brandId: string }; onBack: () => void }) {
  const [cfg, setCfg] = useState<BrandConfig>(() => {
    const c = JSON.parse(JSON.stringify(brand))
    if (!c.levelRules) c.levelRules = {}
    if (!c.disambiguation) c.disambiguation = { maxDisambiguationTokens: 3, rules: [] }
    return c
  })
  const [tab, setTab] = useState('rules')
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState('')
  const [loading, setLoading] = useState(brand.brandId !== '')

  useState(() => {
    if (brand.brandId) {
      API.get(brand.brandId).then(c => {
        c._existing = true
        if (!c.disambiguation) c.disambiguation = { maxDisambiguationTokens: 3, rules: [] }
        setCfg(c)
        setLoading(false)
      }).catch(() => setLoading(false))
    }
  })

  const set = (path: string, val: unknown) => {
    const n = JSON.parse(JSON.stringify(cfg))
    const keys = path.split('.')
    let obj: Record<string, unknown> = n
    for (let i = 0; i < keys.length - 1; i++) {
      if (!obj[keys[i]]) obj[keys[i]] = {}
      obj = obj[keys[i]] as Record<string, unknown>
    }
    obj[keys[keys.length - 1]] = val
    setCfg(n)
  }

  const toggleArr = (path: string, item: string) => {
    const n = JSON.parse(JSON.stringify(cfg))
    const keys = path.split('.')
    let obj: Record<string, unknown> = n
    for (let i = 0; i < keys.length - 1; i++) obj = obj[keys[i]] as Record<string, unknown>
    const arr = obj[keys[keys.length - 1]] as string[]
    if (!arr) obj[keys[keys.length - 1]] = [item]
    else {
      const idx = arr.indexOf(item)
      if (idx >= 0) arr.splice(idx, 1); else arr.push(item)
    }
    setCfg(n)
  }

  const addLevel = (lvl: AuthLevel) => {
    const n = JSON.parse(JSON.stringify(cfg))
    if (!n.levelRules) n.levelRules = {}
    n.levelRules[lvl] = freshRule()
    setCfg(n)
  }

  const removeLevel = (lvl: string) => {
    const n = JSON.parse(JSON.stringify(cfg))
    delete n.levelRules[lvl]
    setCfg(n)
  }

  const addPath = (lvl: string) => {
    const n = JSON.parse(JSON.stringify(cfg))
    const paths = n.levelRules[lvl].paths
    paths.push({ pathIndex: paths.length, description: '', requiredTokens: [], backupTokens: null })
    setCfg(n)
  }

  const removePath = (lvl: string, pi: number) => {
    const n = JSON.parse(JSON.stringify(cfg))
    n.levelRules[lvl].paths = n.levelRules[lvl].paths.filter((p: Path) => p.pathIndex !== pi)
    setCfg(n)
  }

  const toggleToken = (lvl: string, pi: number, tok: TokenType) => {
    const n = JSON.parse(JSON.stringify(cfg))
    const p = n.levelRules[lvl].paths.find((x: Path) => x.pathIndex === pi)!
    if (!p) return
    const idx = p.requiredTokens.indexOf(tok)
    if (idx >= 0) p.requiredTokens.splice(idx, 1); else p.requiredTokens.push(tok)
    setCfg(n)
  }

  const toggleBackup = (lvl: string, pi: number, req: TokenType, alt: TokenType) => {
    const n = JSON.parse(JSON.stringify(cfg))
    const p = n.levelRules[lvl].paths.find((x: Path) => x.pathIndex === pi)!
    if (!p) return
    if (!p.backupTokens) p.backupTokens = {}
    if (!p.backupTokens[req]) p.backupTokens[req] = []
    const idx = p.backupTokens[req].indexOf(alt)
    if (idx >= 0) p.backupTokens[req].splice(idx, 1); else p.backupTokens[req].push(alt)
    if (p.backupTokens[req].length === 0) delete p.backupTokens[req]
    if (Object.keys(p.backupTokens).length === 0) p.backupTokens = null
    setCfg(n)
  }

  const save = async () => {
    setSaving(true)
    try {
      await API.save(cfg)
      setToast('Brand "' + cfg.brandId + '" saved')
      setTimeout(() => setToast(''), 3000)
    } catch { setToast('Save failed'); setTimeout(() => setToast(''), 3000) }
    finally { setSaving(false) }
  }

  const inputCls = 'w-full rounded-lg border border-zinc-700 bg-zinc-800/50 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/50'
  const tagCls = (active: boolean) => cn(
    'inline-flex items-center rounded-md px-2.5 py-1 text-xs font-medium cursor-pointer transition-colors select-none',
    active ? 'bg-indigo-600 text-white' : 'bg-zinc-800 text-zinc-400 hover:text-zinc-200 hover:bg-zinc-700'
  )

  if (loading) return <div className="p-8 text-center text-zinc-500">Loading...</div>

  return (
    <div className="p-8 max-w-5xl mx-auto space-y-6">
      {toast && (
        <div className={cn('fixed bottom-6 right-6 rounded-lg px-4 py-3 text-sm font-medium shadow-lg z-50',
          toast.includes('failed') ? 'bg-red-600 text-white' : 'bg-emerald-600 text-white')}>
          {toast}
        </div>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">{cfg.brandId || 'New Brand'}</h2>
          <p className="text-sm text-zinc-400 mt-1">Edit brand configuration</p>
        </div>
        <div className="flex gap-2">
          <button onClick={onBack}
            className="rounded-lg border border-zinc-700 px-4 py-2.5 text-sm font-medium text-zinc-300 hover:bg-zinc-800 transition-colors"
          >Back</button>
          <button onClick={save} disabled={saving}
            className="rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50 transition-colors"
          >{saving ? 'Saving...' : 'Save'}</button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 rounded-xl bg-zinc-900/50 border border-zinc-800 p-1 w-fit">
        {(['rules', 'disambiguation', 'json'] as const).map(t => (
          <button key={t}
            onClick={() => setTab(t)}
            className={cn('px-4 py-2 rounded-lg text-sm font-medium transition-colors',
              tab === t ? 'bg-zinc-800 text-white' : 'text-zinc-400 hover:text-zinc-200')}
          >{t === 'rules' ? 'Level Rules' : t === 'disambiguation' ? 'Disambiguation' : 'JSON Preview'}</button>
        ))}
      </div>

      {/* Level Rules Tab */}
      {tab === 'rules' && (
        <div className="space-y-6">
          <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 space-y-3">
            <label className="text-sm font-medium text-zinc-300">Brand ID</label>
            <input className={inputCls} value={cfg.brandId}
              onChange={e => set('brandId', e.target.value)} placeholder="e.g. BRAND_C"
              readOnly={cfg._existing} />
          </div>

          {LEVELS.map(lvl => (
            <div key={lvl} className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold text-zinc-200">
                  {lvl}
                  {cfg.levelRules[lvl] && <span className="ml-2 text-xs text-emerald-400 font-normal">configured</span>}
                </h3>
                {cfg.levelRules[lvl]
                  ? <button onClick={() => removeLevel(lvl)}
                      className="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                  : <button onClick={() => addLevel(lvl)}
                      className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">+ Add</button>}
              </div>

              {cfg.levelRules[lvl] && (
                <div className="space-y-4">
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="text-xs text-zinc-400 block mb-1">Max Retries</label>
                      <input type="number" min={1} className={inputCls}
                        value={cfg.levelRules[lvl].maxRetriesPerToken}
                        onChange={e => set('levelRules.' + lvl + '.maxRetriesPerToken', parseInt(e.target.value) || 1)} />
                    </div>
                    <div>
                      <label className="text-xs text-zinc-400 block mb-1">Lockout (sec)</label>
                      <input type="number" min={0} className={inputCls}
                        value={cfg.levelRules[lvl].lockoutSeconds}
                        onChange={e => set('levelRules.' + lvl + '.lockoutSeconds', parseInt(e.target.value) || 0)} />
                    </div>
                  </div>

                  <div className="flex items-center justify-between">
                    <label className="text-xs font-medium text-zinc-400">Paths</label>
                    <button onClick={() => addPath(lvl)}
                      className="text-xs text-indigo-400 hover:text-indigo-300 transition-colors">+ Add Path</button>
                  </div>

                  {cfg.levelRules[lvl].paths.map((p, pi) => (
                    <div key={p.pathIndex} className="rounded-lg border border-zinc-700/50 bg-zinc-800/30 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-zinc-400">
                          Path {pi} {pi === 0 ? '(Primary)' : '(Fallback)'}
                        </span>
                        {cfg.levelRules[lvl].paths.length > 1 &&
                          <button onClick={() => removePath(lvl, p.pathIndex)}
                            className="text-xs text-red-400 hover:text-red-300">Remove</button>}
                      </div>
                      <input className={inputCls} placeholder="Description" value={p.description || ''}
                        onChange={e => set('levelRules.' + lvl + '.paths[' + pi + '].description', e.target.value)} />
                      <div>
                        <label className="text-xs text-zinc-400 block mb-1.5">Required Tokens</label>
                        <div className="flex flex-wrap gap-1.5">
                          {TYPES.map(t => (
                            <span key={t} className={tagCls(p.requiredTokens.includes(t))}
                              onClick={() => toggleToken(lvl, p.pathIndex, t)}>{t}</span>
                          ))}
                        </div>
                      </div>
                      {p.requiredTokens.map(req => (
                        <div key={req}>
                          <label className="text-xs text-zinc-500 block mb-1.5">Backups for <span className="text-zinc-300 font-medium">{req}</span></label>
                          <div className="flex flex-wrap gap-3">
                            {TYPES.filter(t => t !== req).map(t => {
                              const sel = !!(p.backupTokens && p.backupTokens[req] && p.backupTokens[req].includes(t))
                              return (
                                <label key={t} className="flex items-center gap-1.5 text-xs text-zinc-400 cursor-pointer hover:text-zinc-200 select-none">
                                  <input type="checkbox" checked={sel}
                                    onChange={() => toggleBackup(lvl, p.pathIndex, req, t)}
                                    className="rounded border-zinc-600 bg-zinc-800" />
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
              )}
            </div>
          ))}
        </div>
      )}

      {/* Disambiguation Tab */}
      {tab === 'disambiguation' && (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5 space-y-4">
          <h3 className="font-semibold text-zinc-200">Party Disambiguation</h3>
          <p className="text-sm text-zinc-400">Disambiguation is always-on. Configure token limits and filter rules below.</p>

          <div>
            <label className="text-xs text-zinc-400 block mb-1">Max Disambiguation Tokens</label>
            <input type="number" min={1} max={10} className={cn(inputCls, 'w-32')}
              value={cfg.disambiguation?.maxDisambiguationTokens ?? 3}
              onChange={e => set('disambiguation.maxDisambiguationTokens', parseInt(e.target.value) || 3)} />
            <p className="text-xs text-zinc-500 mt-1">Maximum rounds of disambiguation before failing</p>
          </div>

          <div>
            <label className="text-xs text-zinc-400 block mb-2">Filter Rules</label>
            <div className="space-y-2">
              {(['EXCLUDE_INACTIVE', 'PREFER_PRIMARY_ANI'] as const).map(rule => {
                const active = cfg.disambiguation?.rules?.some(r => r.type === rule)
                return (
                  <label key={rule} className="flex items-center gap-2 text-sm text-zinc-300 cursor-pointer hover:text-zinc-100 select-none">
                    <input type="checkbox" checked={!!active}
                      onChange={() => {
                        const n = JSON.parse(JSON.stringify(cfg))
                        if (!n.disambiguation) n.disambiguation = { maxDisambiguationTokens: 3, rules: [] }
                        if (!n.disambiguation.rules) n.disambiguation.rules = []
                        if (active) n.disambiguation.rules = n.disambiguation.rules.filter((r: {type:string}) => r.type !== rule)
                        else n.disambiguation.rules.push({ type: rule })
                        setCfg(n)
                      }}
                      className="rounded border-zinc-600 bg-zinc-800" />
                    <span>
                      <span className="font-medium">{rule === 'EXCLUDE_INACTIVE' ? 'Exclude Inactive' : 'Prefer Primary ANI'}</span>
                      <span className="text-xs text-zinc-500 ml-2">
                        {rule === 'EXCLUDE_INACTIVE' ? 'Remove parties where active=false' : 'Keep only parties where ANI is primary'}
                      </span>
                    </span>
                  </label>
                )
              })}
            </div>
          </div>
        </div>
      )}

      {/* JSON Preview Tab */}
      {tab === 'json' && (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-5">
          <pre className="text-xs text-zinc-300 font-mono overflow-auto max-h-[600px] whitespace-pre-wrap">
            {JSON.stringify(cfg, (key, val) => {
              // Omit internal _existing flag
              if (key === '_existing') return undefined
              // Omit empty disambiguation when it has defaults
              if (key === 'disambiguation' && val && !val.rules?.length && val.maxDisambiguationTokens === 3) return undefined
              return val
            }, 2)}
          </pre>
        </div>
      )}
    </div>
  )
}
