import { useState } from 'react'
import { cn } from './lib/utils'
import { LayoutDashboard, Shield, History, ChevronLeft, ChevronRight, Zap } from 'lucide-react'
import Dashboard from './pages/Dashboard'
import Brands from './pages/Brands'
import BrandEditor from './pages/BrandEditor'
import SessionLog from './pages/SessionLog'

type Page = 'dashboard' | 'brands' | 'editor' | 'sessions'
interface Brand { brandId: string; levelRules?: Record<string, unknown>; disambiguation?: unknown }

const NAV = [
  { id: 'dashboard' as const, label: 'Dashboard', icon: LayoutDashboard },
  { id: 'brands' as const, label: 'Brands', icon: Shield },
  { id: 'sessions' as const, label: 'Session Log', icon: History },
] as const

type NavPage = typeof NAV[number]['id']

export default function App() {
  const [page, setPage] = useState<Page>('dashboard')
  const [editingBrand, setEditingBrand] = useState<Brand | null>(null)
  const [collapsed, setCollapsed] = useState(false)

  const go = (p: NavPage) => { setPage(p); setEditingBrand(null) }

  return (
    <div className="min-h-screen bg-slate-50 flex text-slate-900">
      {/* Sidebar */}
      <aside className={cn(
        'bg-slate-950 flex flex-col shrink-0 transition-[width] duration-200 overflow-hidden',
        collapsed ? 'w-14' : 'w-56'
      )}>
        {/* Logo */}
        <div className={cn(
          'h-14 border-b border-slate-800 flex items-center gap-3 shrink-0',
          collapsed ? 'px-3.5 justify-center' : 'px-4'
        )}>
          <div className="w-7 h-7 rounded-md bg-indigo-600 flex items-center justify-center shrink-0">
            <Zap size={14} className="text-white" />
          </div>
          {!collapsed && (
            <div className="min-w-0 overflow-hidden">
              <div className="text-sm font-bold text-white truncate leading-tight">IVR Auth Engine</div>
              <div className="text-[10px] text-slate-500 truncate font-medium">Admin Console</div>
            </div>
          )}
        </div>

        {/* Nav */}
        <nav className="flex-1 p-2 space-y-0.5 mt-1 overflow-hidden">
          {NAV.map(({ id, label, icon: Icon }) => {
            const active = page === id && !editingBrand
            return (
              <button
                key={id}
                onClick={() => go(id)}
                title={collapsed ? label : undefined}
                className={cn(
                  'w-full rounded-lg text-sm font-medium transition-colors flex items-center gap-2.5 overflow-hidden',
                  collapsed ? 'px-2.5 py-2.5 justify-center' : 'px-3 py-2.5',
                  active
                    ? 'bg-indigo-600/15 text-indigo-400 border border-indigo-500/20'
                    : 'text-slate-500 hover:text-slate-200 hover:bg-slate-800/60 border border-transparent'
                )}
              >
                <Icon size={15} className="shrink-0" />
                {!collapsed && <span className="truncate">{label}</span>}
              </button>
            )
          })}
        </nav>

        {/* Footer */}
        <div className="p-2 border-t border-slate-800 overflow-hidden">
          <button
            onClick={() => setCollapsed(c => !c)}
            className={cn(
              'w-full rounded-lg text-slate-600 hover:text-slate-300 hover:bg-slate-800/60 transition-colors flex items-center gap-2',
              collapsed ? 'px-2.5 py-2 justify-center' : 'px-3 py-2'
            )}
          >
            {collapsed
              ? <ChevronRight size={14} />
              : <><ChevronLeft size={14} /><span className="text-xs font-medium">Collapse</span></>
            }
          </button>
          {!collapsed && (
            <p className="px-3 pt-1.5 pb-0.5 text-[10px] text-slate-700 font-semibold tracking-wider uppercase">
              v1.0.0
            </p>
          )}
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto min-w-0 flex flex-col">
        {page === 'dashboard' && <Dashboard />}
        {page === 'brands' && (
          <Brands onEdit={(b) => { setEditingBrand(b); setPage('editor') }} />
        )}
        {page === 'sessions' && <SessionLog />}
        {page === 'editor' && editingBrand && (
          <BrandEditor
            brand={editingBrand}
            onBack={() => { setEditingBrand(null); setPage('brands') }}
          />
        )}
      </main>
    </div>
  )
}
