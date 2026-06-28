import { useState, useEffect } from 'react'
import { Truck, Plus, Save, Trash2, AlertCircle } from 'lucide-react'
import { cn } from '../lib/utils'
import { getTransferPolicies, saveTransferPolicies, type TransferPolicy } from '../lib/api'
import EmptyState from '../components/EmptyState'

const LEVELS = ['NONE', 'BASIC', 'STANDARD', 'ELEVATED', 'ADMIN'] as const
const TOKENS = ['ACCOUNT_NUMBER', 'PIN', 'OTP', 'SSN_LAST4', 'VOICE_PRINT', 'DATE_OF_BIRTH', 'CARD_LAST4'] as const

function emptyPolicy(): TransferPolicy {
  return { sourceSystemId: '', honoredTokens: ['ACCOUNT_NUMBER'], maxHonoredLevel: 'BASIC', enabled: true }
}

function PolicyCard({
  policy, index, onChange, onRemove,
}: {
  policy: TransferPolicy; index: number; onChange: (p: TransferPolicy) => void; onRemove: () => void
}) {
  const toggleToken = (tok: string) => {
    const has = policy.honoredTokens.includes(tok)
    onChange({
      ...policy,
      honoredTokens: has
        ? policy.honoredTokens.filter(t => t !== tok)
        : [...policy.honoredTokens, tok],
    })
  }

  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm p-5 space-y-5">
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1">
          <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1.5">Source System ID</label>
          <input
            className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30"
            placeholder="e.g. LEGACY_IVR"
            value={policy.sourceSystemId}
            onChange={e => onChange({ ...policy, sourceSystemId: e.target.value })}
          />
        </div>
        <button onClick={onRemove} className="text-slate-400 hover:text-red-600 transition-colors p-1.5 mt-2 shrink-0">
          <Trash2 size={15} />
        </button>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1.5">Max Honored Level</label>
          <select
            className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/30"
            value={policy.maxHonoredLevel}
            onChange={e => onChange({ ...policy, maxHonoredLevel: e.target.value })}
          >
            {LEVELS.map(l => <option key={l} value={l}>{l}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1.5">Status</label>
          <div className="flex items-center gap-3 h-[38px]">
            <label className="flex items-center gap-2 text-sm dark:text-slate-300 cursor-pointer select-none">
              <input
                type="checkbox"
                checked={policy.enabled}
                onChange={e => onChange({ ...policy, enabled: e.target.checked })}
                className="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500"
              />
              Enabled
            </label>
          </div>
        </div>
      </div>

      <div>
        <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">Honored Tokens</label>
        <div className="flex flex-wrap gap-1.5">
          {TOKENS.map(t => {
            const active = policy.honoredTokens.includes(t)
            return (
              <span
                key={t}
                onClick={() => toggleToken(t)}
                className={cn(
                  'inline-flex items-center rounded-lg px-3 py-1.5 text-xs font-bold cursor-pointer transition-colors select-none border',
                  active
                    ? 'bg-indigo-600 text-white border-indigo-600'
                    : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600 hover:border-indigo-300 hover:text-indigo-600'
                )}
              >
                {t}
              </span>
            )
          })}
        </div>
      </div>
    </div>
  )
}

export default function TransferPolicies() {
  const [policies, setPolicies] = useState<TransferPolicy[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null)

  useEffect(() => {
    getTransferPolicies()
      .then(setPolicies)
      .catch(() => setToast({ msg: 'Failed to load transfer policies', ok: false }))
      .finally(() => setLoading(false))
  }, [])

  const showToast = (msg: string, ok: boolean) => {
    setToast({ msg, ok })
    setTimeout(() => setToast(null), 3000)
  }

  const save = async () => {
    setSaving(true)
    try {
      const result = await saveTransferPolicies(policies)
      setPolicies(result)
      showToast('Transfer policies saved', true)
    } catch {
      showToast('Failed to save', false)
    } finally {
      setSaving(false)
    }
  }

  const updatePolicy = (index: number, policy: TransferPolicy) => {
    setPolicies(prev => prev.map((p, i) => i === index ? policy : p))
  }

  const removePolicy = (index: number) => {
    setPolicies(prev => prev.filter((_, i) => i !== index))
  }

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <div className="text-sm text-slate-400 animate-pulse">Loading transfer policies…</div>
      </div>
    )
  }

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

      <div className="bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 px-8 py-4 flex items-center justify-between gap-6 shrink-0">
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Transfer Policies</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
            {policies.length} polic{policies.length !== 1 ? 'ies' : 'y'} configured
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setPolicies(prev => [...prev, emptyPolicy()])}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-slate-600 px-4 py-2.5 text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >
            <Plus size={15} />Add Policy
          </button>
          <button
            onClick={save}
            disabled={saving}
            className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-indigo-500 disabled:opacity-50 transition-colors shadow-sm"
          >
            <Save size={15} />{saving ? 'Saving…' : 'Save All'}
          </button>
        </div>
      </div>

      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-3xl mx-auto space-y-6">
          <div className="rounded-xl border border-amber-200 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-800 p-4 flex items-start gap-3">
            <AlertCircle size={16} className="text-amber-600 dark:text-amber-400 mt-0.5 shrink-0" />
            <p className="text-sm text-amber-800 dark:text-amber-300">
              Transfer policies control what authentication credit an inbound call transfer can carry.
              Changes take effect immediately.
            </p>
          </div>

          {policies.length === 0 ? (
            <EmptyState
              icon={Truck}
              title="No transfer policies"
              subtitle="Click 'Add Policy' to create your first one"
            />
          ) : (
            policies.map((p, i) => (
              <PolicyCard
                key={i}
                policy={p}
                index={i}
                onChange={np => updatePolicy(i, np)}
                onRemove={() => removePolicy(i)}
              />
            ))
          )}
        </div>
      </div>
    </div>
  )
}
