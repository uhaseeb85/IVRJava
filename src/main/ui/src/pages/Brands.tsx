import { useEffect, useState } from 'react'
import { Plus, Trash2, Shield, Search, Pencil, Layers } from 'lucide-react'
import { cn } from '../lib/utils'

interface Brand {
  brandId: string
  levelRules?: Record<string, unknown>
  disambiguation?: Record<string, unknown>
}

const LEVEL_BADGE: Record<string, string> = {
  BASIC:    'text-slate-700 bg-slate-100 border-slate-300',
  STANDARD: 'text-blue-700 bg-blue-50 border-blue-200',
  ELEVATED: 'text-violet-700 bg-violet-50 border-violet-200',
  ADMIN:    'text-orange-700 bg-orange-50 border-orange-200',
}

const API = {
  list: (): Promise<Brand[]> => fetch('/api/brands').then(r => r.json()),
  del: (id: string) => fetch('/api/brands/' + id, { method: 'DELETE' }),
}

function LevelBadge({ level }: { level: string }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-bold',
      LEVEL_BADGE[level] ?? LEVEL_BADGE.BASIC
    )}>
      {level}
    </span>
  )
}

function BrandCard({ brand, onEdit, onDelete }: { brand: Brand; onEdit: () => void; onDelete: () => void }) {
  const levels = brand.levelRules ? Object.keys(brand.levelRules) : []
  const totalPaths = brand.levelRules
    ? Object.values(brand.levelRules as Record<string, { paths?: unknown[] }>)
        .reduce((sum, rule) => sum + (rule?.paths?.length ?? 0), 0)
    : 0

  return (
    <div
      className="bg-white rounded-xl border border-slate-200 shadow-sm hover:shadow-md hover:border-indigo-300 transition-all group cursor-pointer"
      onClick={onEdit}
    >
      <div className="p-5">
        {/* Card header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-indigo-100 flex items-center justify-center shrink-0">
              <Shield size={16} className="text-indigo-600" />
            </div>
            <div className="min-w-0">
              <h3 className="font-bold text-slate-900 truncate">{brand.brandId}</h3>
              <p className="text-xs text-slate-400 mt-0.5">
                {levels.length} level{levels.length !== 1 ? 's' : ''} · {totalPaths} path{totalPaths !== 1 ? 's' : ''}
              </p>
            </div>
          </div>
          <button
            onClick={e => { e.stopPropagation(); onDelete() }}
            className="opacity-0 group-hover:opacity-100 text-slate-400 hover:text-red-600 transition-all p-1.5 rounded-lg hover:bg-red-50 shrink-0"
            title="Delete brand"
          >
            <Trash2 size={14} />
          </button>
        </div>

        {/* Level badges */}
        <div className="flex flex-wrap gap-1.5 mb-4 min-h-[24px]">
          {levels.length > 0
            ? levels.map(l => <LevelBadge key={l} level={l} />)
            : <span className="text-xs text-slate-400 italic">No levels configured</span>
          }
        </div>

        {/* Footer */}
        <div className="pt-3 border-t border-slate-100 flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <Layers size={11} />
            {brand.disambiguation
              ? <span className="text-emerald-600 font-medium">Disambiguation on</span>
              : 'No disambiguation'
            }
          </div>
          <span className="text-xs text-indigo-600 font-semibold flex items-center gap-1 group-hover:underline">
            <Pencil size={11} />Edit
          </span>
        </div>
      </div>
    </div>
  )
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5 animate-pulse">
      <div className="flex items-center gap-3 mb-4">
        <div className="w-9 h-9 rounded-lg bg-slate-200" />
        <div className="space-y-1.5 flex-1">
          <div className="h-4 w-28 bg-slate-200 rounded" />
          <div className="h-3 w-20 bg-slate-100 rounded" />
        </div>
      </div>
      <div className="flex gap-1.5 mb-4">
        <div className="h-5 w-14 bg-slate-100 rounded-full" />
        <div className="h-5 w-20 bg-slate-100 rounded-full" />
      </div>
      <div className="h-px bg-slate-100 mb-3" />
      <div className="h-3 w-32 bg-slate-100 rounded" />
    </div>
  )
}

export default function Brands({ onEdit }: { onEdit: (b: Brand) => void }) {
  const [brands, setBrands] = useState<Brand[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')

  const load = async () => {
    setLoading(true)
    try { setBrands(await API.list()) }
    catch { setError('Failed to load brands — is the backend running?') }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const deleteBrand = async (id: string) => {
    if (!confirm(`Delete brand "${id}"? This cannot be undone.`)) return
    await API.del(id)
    setBrands(prev => prev.filter(b => b.brandId !== id))
  }

  const filtered = brands.filter(b =>
    !search || b.brandId.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <div className="flex flex-col min-h-full">
      {/* Header */}
      <div className="bg-white border-b border-slate-200 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Brands</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            {loading ? 'Loading…' : `${brands.length} brand${brands.length !== 1 ? 's' : ''} configured`}
          </p>
        </div>
        <button
          onClick={() => onEdit({ brandId: '', levelRules: {} })}
          className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 transition-colors shadow-sm"
        >
          <Plus size={15} />New Brand
        </button>
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-5xl mx-auto space-y-6">

          {/* Search bar */}
          {(brands.length > 0 || search) && (
            <div className="relative">
              <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
              <input
                className="w-full max-w-xs rounded-lg border border-slate-200 bg-white pl-9 pr-4 py-2 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition shadow-sm"
                placeholder="Search brands…"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
          )}

          {error && (
            <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-600">{error}</div>
          )}

          {loading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {[1, 2, 3].map(i => <SkeletonCard key={i} />)}
            </div>
          ) : filtered.length === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-200 bg-white p-16 text-center">
              <div className="w-12 h-12 rounded-xl bg-slate-100 flex items-center justify-center mx-auto mb-4">
                <Shield size={20} className="text-slate-400" />
              </div>
              <p className="font-semibold text-slate-600">
                {search ? `No brands match "${search}"` : 'No brands configured'}
              </p>
              <p className="text-sm text-slate-400 mt-1">
                {search ? 'Try a different search term' : 'Create your first brand to get started'}
              </p>
              {!search && (
                <button
                  onClick={() => onEdit({ brandId: '', levelRules: {} })}
                  className="mt-5 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 transition-colors"
                >
                  <Plus size={15} />Create Brand
                </button>
              )}
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {filtered.map(b => (
                <BrandCard
                  key={b.brandId}
                  brand={b}
                  onEdit={() => onEdit(b)}
                  onDelete={() => deleteBrand(b.brandId)}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
