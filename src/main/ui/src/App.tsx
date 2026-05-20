import { useState } from 'react'
import { cn } from './lib/utils'
import Dashboard from './pages/Dashboard'
import Brands from './pages/Brands'
import BrandEditor from './pages/BrandEditor'

type Page = 'dashboard' | 'brands' | 'editor'

interface Brand { brandId: string; levelRules?: Record<string, unknown>; disambiguation?: unknown }

export default function App() {
  const [page, setPage] = useState<Page>('dashboard')
  const [editingBrand, setEditingBrand] = useState<Brand | null>(null)

  const navItems: { id: Page; label: string }[] = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'brands', label: 'Brands' },
  ]

  const handleEdit = (brand: Brand) => {
    setEditingBrand(brand)
    setPage('editor')
  }

  const handleBack = () => {
    setEditingBrand(null)
    setPage('brands')
  }

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 flex">
      {/* Sidebar */}
      <aside className="w-56 border-r border-zinc-800 bg-zinc-900/50 flex flex-col shrink-0">
        <div className="p-4 border-b border-zinc-800">
          <h1 className="text-lg font-semibold tracking-tight">IVR Auth Engine</h1>
          <p className="text-xs text-zinc-500 mt-0.5">Configuration &amp; Testing</p>
        </div>
        <nav className="flex-1 p-3 space-y-1">
          {navItems.map((item) => (
            <button
              key={item.id}
              onClick={() => { setPage(item.id); setEditingBrand(null) }}
              className={cn(
                'w-full text-left px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                page === item.id && !editingBrand
                  ? 'bg-zinc-800 text-white'
                  : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/50'
              )}
            >
              {item.label}
            </button>
          ))}
        </nav>
        <div className="p-3 border-t border-zinc-800 text-xs text-zinc-600">
          v1.0.0
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        {page === 'dashboard' && <Dashboard />}
        {page === 'brands' && <Brands onEdit={handleEdit} />}
        {page === 'editor' && editingBrand && <BrandEditor brand={editingBrand} onBack={handleBack} />}
      </main>
    </div>
  )
}
