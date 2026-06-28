// Shared form-input styling. The two variants differ only in their disabled
// treatment: the standard input dims, the editor input fills gray (used where a
// field is locked, e.g. the Brand ID of an existing brand).
const base = 'w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 px-3 py-2 text-sm dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500/30 focus:border-indigo-400 transition'

export const inputCls = `${base} disabled:opacity-50 disabled:cursor-not-allowed`

export const editorInputCls = `${base} text-slate-800 dark:text-slate-200 disabled:bg-slate-50 dark:disabled:bg-slate-600 disabled:cursor-not-allowed`
