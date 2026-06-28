const COLORS: Record<string, string> = {
  Running: 'bg-green-100 text-green-700',
  Ready: 'bg-green-100 text-green-700',
  Succeeded: 'bg-blue-100 text-blue-700',
  Active: 'bg-green-100 text-green-700',
  Normal: 'bg-gray-100 text-gray-600',
  Pending: 'bg-yellow-100 text-yellow-700',
  Terminating: 'bg-orange-100 text-orange-700',
  Failed: 'bg-red-100 text-red-700',
  NotReady: 'bg-red-100 text-red-700',
  Warning: 'bg-red-100 text-red-700',
  Unknown: 'bg-gray-100 text-gray-500',
}

interface Props {
  status: string
  className?: string
}

export function StatusBadge({ status, className = '' }: Props) {
  const color = COLORS[status] ?? 'bg-gray-100 text-gray-600'
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${color} ${className}`}>
      {status}
    </span>
  )
}
