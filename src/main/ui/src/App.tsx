import { useState, useEffect } from 'react'
import { cn } from './lib/utils'
import {
  LayoutDashboard, Shield, History, ChevronLeft, ChevronRight, Zap,
  PhoneOff, Activity, Moon, Sun, Truck,
} from 'lucide-react'
import Dashboard from './pages/Dashboard'
import Brands from './pages/Brands'
import BrandEditor from './pages/BrandEditor'
import SessionLog from './pages/SessionLog'
import TransferPolicies from './pages/TransferPolicies'
import ActiveSessions from './pages/ActiveSessions'
import LookupServices from './pages/LookupServices'

type Page = 'dashboard' | 'brands' | 'editor' | 'sessions' | 'transfers' | 'active-sessions' | 'lookup-services'
interface Brand { brandId: string; levelRules?: Record<string, unknown> }

const NAV = [
  { id: 'dashboard' as const, label: 'Dashboard', icon: LayoutDashboard },
  { id: 'brands' as const, label: 'Brands', icon: Shield },
  { id: 'sessions' as const, label: 'Session Log', icon: History },
  { id: 'active-sessions' as const, label: 'Active Sessions', icon: PhoneOff },
  { id: 'transfers' as const, label: 'Transfer Policies', icon: Truck },
  { id: 'lookup-services' as const, label: 'Lookup Services', icon: Activity },
] as const

type NavPage = typeof NAV[number]['id']

export default function App() {
  const [page, setPage] = useState<Page>('dashboard')
  const [editingBrand, setEditingBrand] = useState<Brand | null>(null)
  const [collapsed, setCollapsed] = useState(false)
  const [dark, setDark] = useState(() => localStorage.getItem('ivr-dark-mode') === 'true')

  useEffect(() => {
    localStorage.setItem('ivr-dark-mode', String(dark))
    document.documentElement.classList.toggle('dark', dark)
  }, [dark])

  const go = (p: NavPage) => { setPage(p); setEditingBrand(null) }

  return (
    <div className={cn(
      'min-h-screen flex',
      dark ? 'dark bg-slate-900 text-slate-100' : 'bg-slate-50 text-slate-900'
    )}>
      {/* Sidebar */}
      <aside className={cn(
        'flex flex-col shrink-0 transition-[width] duration-200 overflow-hidden border-r',
        dark ? 'bg-slate-950 border-slate-800' : 'bg-white border-slate-200',
        collapsed ? 'w-14' : 'w-56'
      )}>
        {/* Logo */}
        <div className={cn(
          'h-14 flex items-center gap-3 shrink-0',
          dark ? 'border-b border-slate-800' : 'border-b border-slate-200',
          collapsed ? 'px-3.5 justify-center' : 'px-4'
        )}>
          <div className="w-7 h-7 rounded-md bg-indigo-600 flex items-center justify-center shrink-0">
            <Zap size={14} className="text-white" />
          </div>
          {!collapsed && (
            <div className="min-w-0 overflow-hidden">
              <div className={cn('text-sm font-bold truncate leading-tight', dark ? 'text-white' : 'text-slate-900')}>IVR Auth Engine</div>
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
                    ? cn(
                        'border',
                        dark
                          ? 'bg-indigo-600/15 text-indigo-400 border-indigo-500/20'
                          : 'bg-indigo-50 text-indigo-600 border-indigo-200'
                      )
                    : cn(
                        'border border-transparent',
                        dark
                          ? 'text-slate-500 hover:text-slate-200 hover:bg-slate-800/60'
                          : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
                      )
                )}
              >
                <Icon size={15} className="shrink-0" />
                {!collapsed && <span className="truncate">{label}</span>}
              </button>
            )
          })}
        </nav>

        {/* Footer */}
        <div className={cn('p-2 space-y-1 overflow-hidden', dark ? 'border-t border-slate-800' : 'border-t border-slate-200')}>
          {/* Dark mode toggle */}
          <button
            onClick={() => setDark(d => !d)}
            title={dark ? 'Light mode' : 'Dark mode'}
            className={cn(
              'w-full rounded-lg transition-colors flex items-center gap-2',
              collapsed ? 'px-2.5 py-2 justify-center' : 'px-3 py-2',
              dark
                ? 'text-slate-600 hover:text-slate-300 hover:bg-slate-800/60'
                : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
            )}
          >
            {dark ? <Sun size={14} /> : <Moon size={14} />}
            {!collapsed && <span className="text-xs font-medium">Dark Mode</span>}
          </button>
          <button
            onClick={() => setCollapsed(c => !c)}
            className={cn(
              'w-full rounded-lg transition-colors flex items-center gap-2',
              collapsed ? 'px-2.5 py-2 justify-center' : 'px-3 py-2',
              dark
                ? 'text-slate-600 hover:text-slate-300 hover:bg-slate-800/60'
                : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
            )}
          >
            {collapsed
              ? <ChevronRight size={14} />
              : <><ChevronLeft size={14} /><span className="text-xs font-medium">Collapse</span></>
            }
          </button>
          {!collapsed && (
            <p className={cn('px-3 pt-1 pb-0.5 text-[10px] font-semibold tracking-wider uppercase', dark ? 'text-slate-700' : 'text-slate-400')}>
              v1.0.0
            </p>
          )}
        </div>
      </aside>

      {/* Main content */}
      <main className={cn(
        'flex-1 overflow-auto min-w-0 flex flex-col',
        dark && 'dark'
      )}>
        {page === 'dashboard' && <Dashboard />}
        {page === 'brands' && (
          <Brands onEdit={(b) => { setEditingBrand(b); setPage('editor') }} />
        )}
        {page === 'sessions' && <SessionLog />}
        {page === 'transfers' && <TransferPolicies />}
        {page === 'active-sessions' && <ActiveSessions />}
        {page === 'lookup-services' && <LookupServices />}
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
