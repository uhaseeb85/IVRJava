import { useEffect, useState } from 'react'

interface Brand {
  brandId: string
  levelRules?: Record<string, unknown>
  disambiguation?: Record<string, unknown>
}

const API = {
  list: () => fetch('/api/brands').then(r => r.json()),
  del: (id: string) => fetch('/api/brands/' + id, { method: 'DELETE' })
}

export default function Brands({ onEdit }: { onEdit: (b: Brand) => void }) {
  const [brands, setBrands] = useState<Brand[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async () => {
    try {
      setBrands(await API.list())
    } catch { setError('Failed to load brands') }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const deleteBrand = async (id: string) => {
    if (!confirm('Delete brand "' + id + '"?')) return
    await API.del(id)
    setBrands(brands.filter(b => b.brandId !== id))
  }

  return (
    <div className="p-8 max-w-5xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Brands</h2>
          <p className="text-sm text-zinc-400 mt-1">Manage brand authentication configurations</p>
        </div>
        <button
          onClick={() => onEdit({ brandId: '', levelRules: {} })}
          className="rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors"
        >+ Create New</button>
      </div>

      {error && <div className="rounded-xl border border-red-900/50 bg-red-950/30 p-4 text-sm text-red-400">{error}</div>}

      {loading ? (
        <div className="text-center py-12 text-zinc-500">Loading...</div>
      ) : brands.length === 0 ? (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 p-12 text-center">
          <p className="text-zinc-500 text-sm">No brands configured.</p>
          <button onClick={() => onEdit({ brandId: '', levelRules: {} })}
            className="mt-4 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors"
          >Create your first brand</button>
        </div>
      ) : (
        <div className="rounded-xl border border-zinc-800 bg-zinc-900/50 overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="border-b border-zinc-800">
                <th className="text-left px-4 py-3 text-xs font-medium text-zinc-400 uppercase tracking-wider">Brand ID</th>
                <th className="text-left px-4 py-3 text-xs font-medium text-zinc-400 uppercase tracking-wider">Levels</th>
                <th className="w-20 px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {brands.map(b => (
                <tr key={b.brandId} className="border-b border-zinc-800/50 hover:bg-zinc-800/30 cursor-pointer transition-colors"
                  onClick={() => onEdit(b)}>
                  <td className="px-4 py-3 text-sm font-medium text-zinc-200">{b.brandId}</td>
                  <td className="px-4 py-3 text-sm text-zinc-400">
                    {b.levelRules ? Object.keys(b.levelRules).join(', ') : '—'}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={e => { e.stopPropagation(); deleteBrand(b.brandId) }}
                      className="text-xs text-red-400 hover:text-red-300 transition-colors"
                    >Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
