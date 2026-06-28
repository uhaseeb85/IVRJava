import { XCircle, ChevronDown, ChevronUp } from 'lucide-react'

// Network / server error banner with an expandable raw-body section.
// `errorBody === '(no details)'` is the sentinel for "expanded but empty".
export default function ErrorBanner({ error, errorBody, setErrorBody }: {
  error: string
  errorBody: string
  setErrorBody: (body: string) => void
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-xl border border-red-200 dark:border-red-800 shadow-sm overflow-hidden">
      <button
        onClick={() => errorBody ? setErrorBody('') : setErrorBody('(no details)')}
        className="w-full p-4 flex items-center gap-2 text-sm text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors text-left"
      >
        <XCircle size={15} className="shrink-0" />
        <span className="flex-1 font-medium">{error}</span>
        {errorBody && (errorBody === '(no details)' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
      </button>
      {errorBody && errorBody !== '(no details)' && (
        <pre className="bg-red-50 dark:bg-red-900/20 border-t border-red-100 dark:border-red-800 text-red-800 dark:text-red-300 p-4 text-xs font-mono overflow-auto max-h-44 leading-relaxed">
          {errorBody}
        </pre>
      )}
    </div>
  )
}
