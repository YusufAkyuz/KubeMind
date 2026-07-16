interface Props {
  message: string
}

export function ErrorBanner({ message }: Props) {
  return (
    <div className="rounded-lg border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3 text-sm text-red-700 dark:text-red-400">
      {message}
    </div>
  )
}

export function EmptyState({ message }: Props) {
  return (
    <div className="rounded-lg border border-dashed border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900 px-6 py-12 text-center">
      <p className="text-sm text-gray-400 dark:text-slate-500">{message}</p>
    </div>
  )
}
