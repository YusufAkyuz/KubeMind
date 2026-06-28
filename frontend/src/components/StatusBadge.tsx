const VARIANTS: Record<string, string> = {
  // Green — healthy
  Running: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  Ready: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  Succeeded: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  Active: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  // Yellow — transitional
  Pending: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  Terminating: 'bg-amber-50 text-amber-700 ring-amber-600/20',
  // Red — degraded
  Failed: 'bg-red-50 text-red-700 ring-red-600/20',
  NotReady: 'bg-red-50 text-red-700 ring-red-600/20',
  Warning: 'bg-red-50 text-red-700 ring-red-600/20',
  // Gray — neutral / informational
  Normal: 'bg-gray-100 text-gray-600 ring-gray-500/20',
  Unknown: 'bg-gray-100 text-gray-500 ring-gray-400/20',
}

interface Props {
  status: string
  className?: string
}

export function StatusBadge({ status, className = '' }: Props) {
  const variant = VARIANTS[status] ?? 'bg-gray-100 text-gray-600 ring-gray-500/20'
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${variant} ${className}`}
    >
      {status}
    </span>
  )
}
