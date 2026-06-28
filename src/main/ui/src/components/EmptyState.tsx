import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

// Dashed-border empty-state card shared by list pages (Brands, Session Log).
export default function EmptyState({ icon: Icon, title, subtitle, children }: {
  icon: LucideIcon
  title: string
  subtitle?: string
  children?: ReactNode
}) {
  return (
    <div className="rounded-xl border border-dashed border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-16 text-center">
      <div className="w-12 h-12 rounded-xl bg-slate-100 dark:bg-slate-700 flex items-center justify-center mx-auto mb-4">
        <Icon size={20} className="text-slate-400 dark:text-slate-500" />
      </div>
      <p className="font-semibold text-slate-600 dark:text-slate-300">{title}</p>
      {subtitle && <p className="text-sm text-slate-400 dark:text-slate-500 mt-1">{subtitle}</p>}
      {children}
    </div>
  )
}
