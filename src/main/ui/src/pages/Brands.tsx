import { useEffect, useState, useRef } from 'react'
import { Plus, Trash2, Shield, Search, Pencil, Layers, Download, Upload, Copy } from 'lucide-react'
import { cn } from '../lib/utils'
import { getBrands, deleteBrand as apiDeleteBrand, cloneBrand, exportBrandJson, importBrandFromJson } from '../lib/api'
import EmptyState from '../components/EmptyState'

interface Brand {
  brandId: string
  levelRules?: Record<string, unknown>
}

const LEVEL_BADGE: Record<string, string> = {
  BASIC:    'text-slate-700 bg-slate-100 border-slate-300',
  STANDARD: 'text-blue-700 bg-blue-50 border-blue-200',
  ELEVATED: 'text-violet-700 bg-violet-50 border-violet-200',
  ADMIN:    'text-orange-700 bg-orange-50 border-orange-200',
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

function BrandCard({ brand, onEdit, onDelete, onClone, onExport }: {
  brand: Brand; onEdit: () => void; onDelete: () => void; onClone: () => void; onExport: () => void
}) {
  const levels = brand.levelRules ? Object.keys(brand.levelRules) : []
  const totalPaths = brand.levelRules
    ? Object.values(brand.levelRules as Record<string, { paths?: unknown[] }>)
        .reduce((sum, rule) => sum + (rule?.paths?.length ?? 0), 0)
    : 0

  return (
    <div
      className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm hover:shadow-md hover:border-indigo-300 dark:hover:border-indigo-600 transition-all group cursor-pointer"
      onClick={onEdit}
    >
      <div className="p-5">
        {/* Card header */}
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-indigo-100 dark:bg-indigo-900/50 flex items-center justify-center shrink-0">
              <Shield size={16} className="text-indigo-600 dark:text-indigo-400" />
            </div>
            <div className="min-w-0">
              <h3 className="font-bold text-slate-900 dark:text-slate-100 truncate">{brand.brandId}</h3>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                {levels.length} level{levels.length !== 1 ? 's' : ''} · {totalPaths} path{totalPaths !== 1 ? 's' : ''}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-all">
            <button
              onClick={e => { e.stopPropagation(); onClone() }}
              className="text-slate-400 hover:text-indigo-600 transition-colors p-1.5 rounded-lg hover:bg-indigo-50 dark:hover:bg-indigo-900/30"
              title="Clone brand"
            >
              <Copy size={13} />
            </button>
            <button
              onClick={e => { e.stopPropagation(); onExport() }}
              className="text-slate-400 hover:text-emerald-600 transition-colors p-1.5 rounded-lg hover:bg-emerald-50 dark:hover:bg-emerald-900/30"
              title="Export JSON"
            >
              <Download size={13} />
            </button>
            <button
              onClick={e => { e.stopPropagation(); onDelete() }}
              className="text-slate-400 hover:text-red-600 transition-colors p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/30"
              title="Delete brand"
            >
              <Trash2 size={13} />
            </button>
          </div>
        </div>

        {/* Level badges */}
        <div className="flex flex-wrap gap-1.5 mb-4 min-h-[24px]">
          {levels.length > 0
            ? levels.map(l => <LevelBadge key={l} level={l} />)
            : <span className="text-xs text-slate-400 dark:text-slate-500 italic">No levels configured</span>
          }
        </div>

        {/* Footer */}
        <div className="pt-3 border-t border-slate-100 dark:border-slate-700 flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500">
            <Layers size={11} />
            {totalPaths} auth path{totalPaths !== 1 ? 's' : ''}
          </div>
          <span className="text-xs text-indigo-600 dark:text-indigo-400 font-semibold flex items-center gap-1 group-hover:underline">
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
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null)
  const [cloneDialog, setCloneDialog] = useState<string | null>(null)
  const [cloneName, setCloneName] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const showToast = (msg: string, ok: boolean) => {
    setToast({ msg, ok })
    setTimeout(() => setToast(null), 3000)
  }

  const load = async () => {
    setLoading(true)
    try { setBrands(await getBrands()) }
    catch { setError('Failed to load brands — is the backend running?') }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const deleteBrand = async (id: string) => {
    if (!confirm(`Delete brand "${id}"? This cannot be undone.`)) return
    await apiDeleteBrand(id)
    setBrands(prev => prev.filter(b => b.brandId !== id))
  }

  const handleClone = async () => {
    if (!cloneDialog || !cloneName.trim()) return
    try {
      await cloneBrand(cloneDialog, cloneName.trim())
      showToast(`Brand "${cloneName}" created from clone`, true)
      setCloneDialog(null)
      setCloneName('')
      load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : 'Clone failed', false)
    }
  }

  const handleExport = async (id: string) => {
    try {
      const blob = await exportBrandJson(id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${id.toLowerCase()}.json`
      a.click()
      URL.revokeObjectURL(url)
      showToast(`Exported "${id}"`, true)
    } catch {
      showToast('Export failed', false)
    }
  }

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    try {
      const text = await file.text()
      await importBrandFromJson(text)
      showToast(`Imported "${file.name}"`, true)
      load()
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Import failed', false)
    }
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const filtered = brands.filter(b =>
    !search || b.brandId.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <div className="flex flex-col min-h-full">
      {toast && (
        <div className={cn(
          'fixed bottom-6 right-6 rounded-xl px-5 py-3 text-sm font-semibold shadow-xl z-50 transition-all',
          toast.ok ? 'bg-slate-900 dark:bg-slate-700 text-white' : 'bg-red-600 text-white'
        )}>
          {toast.msg}
        </div>
      )}

      {/* Clone dialog */}
      {cloneDialog && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" onClick={() => setCloneDialog(null)}>
          <div className="bg-white dark:bg-slate-800 rounded-xl shadow-xl border border-slate-200 dark:border-slate-700 p-6 w-96 max-w-full mx-4" onClick={e => e.stopPropagation()}>
            <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-1">Clone Brand</h3>
            <p className="text-sm text-slate-500 dark:text-slate-400 mb-4">
              Create a copy of <span className="font-semibold text-slate-700 dark:text-slate-300">{cloneDialog}</span> with a new ID:
            </p>
            <input
              className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2.5 text-sm dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 mb-4"
              placeholder="e.g. BRAND_C"
              value={cloneName}
              autoFocus
              onChange={e => setCloneName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleClone()}
            />
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setCloneDialog(null)}
                className="px-4 py-2 text-sm font-semibold text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleClone}
                disabled={!cloneName.trim()}
                className="px-4 py-2 rounded-lg bg-indigo-600 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 transition-colors"
              >
                Clone
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Hidden file input for import */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".json"
        className="hidden"
        onChange={handleImport}
      />

      {/* Header */}
      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Brands</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {loading ? 'Loading…' : `${brands.length} brand${brands.length !== 1 ? 's' : ''} configured`}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-slate-600 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >
            <Upload size={15} />Import
          </button>
          <button
            onClick={() => onEdit({ brandId: '', levelRules: {} })}
            className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 transition-colors shadow-sm"
          >
            <Plus size={15} />New Brand
          </button>
        </div>
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-5xl mx-auto space-y-6">

          {/* Search bar */}
          {(brands.length > 0 || search) && (
            <div className="relative">
              <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400 pointer-events-none" />
              <input
                className="w-full max-w-xs rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 pl-9 pr-4 py-2 text-sm dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition shadow-sm"
                placeholder="Search brands…"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
          )}

          {error && (
            <div className="rounded-xl border border-red-200 bg-red-50 dark:bg-red-900/20 dark:border-red-800 p-4 text-sm text-red-600 dark:text-red-400">{error}</div>
          )}

          {loading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {[1, 2, 3].map(i => <SkeletonCard key={i} />)}
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Shield}
              title={search ? `No brands match "${search}"` : 'No brands configured'}
              subtitle={search ? 'Try a different search term' : 'Create your first brand to get started'}
            >
              {!search && (
                <button
                  onClick={() => onEdit({ brandId: '', levelRules: {} })}
                  className="mt-5 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 transition-colors"
                >
                  <Plus size={15} />Create Brand
                </button>
              )}
            </EmptyState>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {filtered.map(b => (
                <BrandCard
                  key={b.brandId}
                  brand={b}
                  onEdit={() => onEdit(b)}
                  onDelete={() => deleteBrand(b.brandId)}
                  onClone={() => { setCloneDialog(b.brandId); setCloneName('') }}
                  onExport={() => handleExport(b.brandId)}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
