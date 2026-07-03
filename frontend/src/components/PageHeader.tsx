import type { ReactNode } from 'react'

interface Props {
  title: string
  subtitle?: string
  count?: number
  noun?: string
  actions?: ReactNode
}

export function PageHeader({ title, subtitle, count, noun, actions }: Props) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-gray-900">{title}</h1>
        {subtitle && <p className="mt-0.5 text-sm text-gray-500">{subtitle}</p>}
      </div>
      <div className="flex items-center gap-3">
        {count !== undefined && noun && (
          <span className="text-sm text-gray-400">
            {count} {count === 1 ? noun : noun + 's'}
          </span>
        )}
        {actions}
      </div>
    </div>
  )
}
