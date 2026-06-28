import { useState, useEffect } from 'react'
import { Activity, CheckCircle, XCircle, Link2, RefreshCw } from 'lucide-react'
import { cn } from '../lib/utils'
import { getLookupServices, getLookupBindings, type LookupService, type LookupBindings } from '../lib/api'
import EmptyState from '../components/EmptyState'

function TokenBadge({ token, active }: { token: string; active?: boolean }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-lg px-2.5 py-1 text-[10px] font-bold border',
      active
        ? 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border-indigo-200 dark:border-indigo-700'
        : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600'
    )}>
      {token}
    </span>
  )
}

function ServiceCard({ service, isBound }: { service: LookupService; isBound: boolean }) {
  const statusColor = service.status === 'REGISTERED' ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600'
  const statusBg = service.status === 'REGISTERED' ? 'bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800' : 'bg-amber-50'

  return (
    <div className={cn(
      'bg-white dark:bg-slate-800 rounded-xl border shadow-sm p-5',
      isBound ? 'border-indigo-200 dark:border-indigo-700' : 'border-slate-200 dark:border-slate-700'
    )}>
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className={cn(
            'w-9 h-9 rounded-lg flex items-center justify-center shrink-0',
            isBound ? 'bg-indigo-100 dark:bg-indigo-900/50' : 'bg-slate-100 dark:bg-slate-700'
          )}>
            <Activity size={16} className={isBound ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-500'} />
          </div>
          <div>
            <h3 className="font-bold text-slate-900 dark:text-slate-100 text-sm">{service.id}</h3>
            <div className="flex items-center gap-1.5 mt-0.5">
              {isBound && (
                <span className="text-[10px] font-bold text-indigo-600 dark:text-indigo-400 flex items-center gap-1">
                  <Link2 size={10} />Bound
                </span>
              )}
              <span className={cn('text-[10px] font-semibold flex items-center gap-1', service.status === 'REGISTERED' ? 'text-emerald-600' : 'text-amber-600')}>
                {service.status === 'REGISTERED' ? <CheckCircle size={10} /> : <XCircle size={10} />}
                {service.status}
              </span>
            </div>
          </div>
        </div>
      </div>

      <div>
        <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">Supported Tokens ({service.supportedTokens.length})</p>
        <div className="flex flex-wrap gap-1.5">
          {service.supportedTokens.length > 0
            ? service.supportedTokens.map(t => <TokenBadge key={t} token={t} />)
            : <span className="text-xs text-slate-400 italic">None</span>
          }
        </div>
      </div>
    </div>
  )
}

function BindingRow({ token, binding }: { token: string; binding: { serviceId: string; failClosed: boolean; params?: Record<string, string> } }) {
  return (
    <div className="flex items-center gap-4 py-2.5 px-4 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700">
      <TokenBadge token={token} active />
      <span className="text-sm text-slate-300 dark:text-slate-600">→</span>
      <span className="text-sm font-bold text-slate-700 dark:text-slate-300">{binding.serviceId}</span>
      <span className={cn(
        'text-[10px] font-bold px-2 py-0.5 rounded-full border',
        binding.failClosed
          ? 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800'
          : 'text-emerald-600 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-900/20 border-emerald-200 dark:border-emerald-800'
      )}>
        {binding.failClosed ? 'Fail Closed' : 'Fail Open'}
      </span>
      {binding.params && Object.keys(binding.params).length > 0 && (
        <span className="text-[10px] text-slate-400 font-mono">
          params: {JSON.stringify(binding.params)}
        </span>
      )}
    </div>
  )
}

export default function LookupServices() {
  const [services, setServices] = useState<LookupService[]>([])
  const [bindings, setBindings] = useState<LookupBindings | null>(null)
  const [loading, setLoading] = useState(true)

  const load = async () => {
    setLoading(true)
    try {
      const [svcs, bnds] = await Promise.all([
        getLookupServices(),
        getLookupBindings(),
      ])
      setServices(svcs)
      setBindings(bnds)
    } catch {
      setServices([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  const boundServiceIds = new Set(Object.values(bindings?.bindings ?? {}).map(b => b.serviceId))

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-sm text-slate-400 animate-pulse">Loading lookup services…</div>
      </div>
    )
  }

  return (
    <div className="flex flex-col min-h-full">
      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Lookup Services</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {services.length} backend service{services.length !== 1 ? 's' : ''} · {Object.keys(bindings?.bindings ?? {}).length} active binding{Object.keys(bindings?.bindings ?? {}).length !== 1 ? 's' : ''}
          </p>
        </div>
        <button
          onClick={load}
          className="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-slate-600 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
        >
          <RefreshCw size={15} />Refresh
        </button>
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-5xl mx-auto space-y-8">
          {/* Active Bindings */}
          {bindings && Object.keys(bindings.bindings).length > 0 && (
            <div>
              <h2 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-3 flex items-center gap-2">
                <Link2 size={13} />Active Token Bindings
              </h2>
              <div className="space-y-2">
                {Object.entries(bindings.bindings).map(([token, binding]) => (
                  <BindingRow key={token} token={token} binding={binding} />
                ))}
              </div>
            </div>
          )}

          {/* No bindings message */}
          {bindings && Object.keys(bindings.bindings).length === 0 && (
            <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-6 text-center">
              <Link2 size={24} className="mx-auto text-slate-300 dark:text-slate-600 mb-3" />
              <p className="text-sm font-semibold text-slate-500 dark:text-slate-400">No active token bindings</p>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                No tokens are wired for backend verification. All tokens pass format check only.
              </p>
            </div>
          )}

          {/* Registered Services */}
          <div>
            <h2 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <Activity size={13} />Registered Services
            </h2>
            {services.length === 0 ? (
              <EmptyState
                icon={Activity}
                title="No lookup services registered"
                subtitle="Backend verification services will appear here when implemented"
              />
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {services.map(s => (
                  <ServiceCard key={s.id} service={s} isBound={boundServiceIds.has(s.id)} />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
