const VARIANTS: Record<string, string> = {
  // Green — healthy
  Running: 'bg-emerald-50 dark:bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-emerald-600/20 dark:ring-emerald-500/30',
  Ready: 'bg-emerald-50 dark:bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-emerald-600/20 dark:ring-emerald-500/30',
  Succeeded: 'bg-emerald-50 dark:bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-emerald-600/20 dark:ring-emerald-500/30',
  Active: 'bg-emerald-50 dark:bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 ring-emerald-600/20 dark:ring-emerald-500/30',
  // Yellow — transitional
  Pending: 'bg-amber-50 dark:bg-amber-500/10 text-amber-700 dark:text-amber-400 ring-amber-600/20 dark:ring-amber-500/30',
  Terminating: 'bg-amber-50 dark:bg-amber-500/10 text-amber-700 dark:text-amber-400 ring-amber-600/20 dark:ring-amber-500/30',
  // Red — degraded
  Failed: 'bg-red-50 dark:bg-red-500/10 text-red-700 dark:text-red-400 ring-red-600/20 dark:ring-red-500/30',
  NotReady: 'bg-red-50 dark:bg-red-500/10 text-red-700 dark:text-red-400 ring-red-600/20 dark:ring-red-500/30',
  Warning: 'bg-red-50 dark:bg-red-500/10 text-red-700 dark:text-red-400 ring-red-600/20 dark:ring-red-500/30',
  // Gray — neutral / informational
  Normal: 'bg-gray-100 dark:bg-neutral-700 text-gray-600 dark:text-neutral-300 ring-gray-500/20 dark:ring-neutral-500/30',
  Unknown: 'bg-gray-100 dark:bg-neutral-700 text-gray-500 dark:text-neutral-400 ring-gray-400/20 dark:ring-neutral-500/30',
}

interface Props {
  status: string
  className?: string
}

export function StatusBadge({ status: rawStatus, className = '' }: Props) {
  const status = rawStatus === 'Unknown' ? 'Pending' : rawStatus
  const variant = VARIANTS[status] ?? 'bg-gray-100 dark:bg-neutral-700 text-gray-600 dark:text-neutral-300 ring-gray-500/20 dark:ring-neutral-500/30'
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${variant} ${className}`}
    >
      {status}
    </span>
  )
}
