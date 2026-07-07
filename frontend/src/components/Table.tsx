import type { ReactNode } from 'react'

export interface Column {
  key: string
  label: string
  className?: string
}

/** Inserts a "Namespace" column right after the first (Name) column when viewing all namespaces. */
export function withNamespaceColumn(columns: Column[], show: boolean): Column[] {
  if (!show) return columns
  return [columns[0], { key: 'namespace', label: 'Namespace' }, ...columns.slice(1)]
}

interface Props {
  columns: Column[]
  children: ReactNode
  minWidth?: string
}

export function Table({ columns, children, minWidth = '600px' }: Props) {
  return (
    <div className="overflow-x-auto rounded-xl ring-1 ring-gray-200 bg-white shadow-sm">
      <table className="w-full text-sm" style={{ minWidth }}>
        <thead>
          <tr className="border-b border-gray-200 bg-gray-50/60">
            {columns.map((col) => (
              <th
                key={col.key}
                className={`px-4 py-3 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-wider whitespace-nowrap ${col.className ?? ''}`}
              >
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">{children}</tbody>
      </table>
    </div>
  )
}

interface RowProps {
  children: ReactNode
  onClick?: () => void
  highlighted?: boolean
}

export function Tr({ children, onClick, highlighted }: RowProps) {
  return (
    <tr
      onClick={onClick}
      className={[
        onClick ? 'cursor-pointer' : '',
        highlighted ? 'bg-blue-50' : 'hover:bg-gray-50',
        'transition-colors',
      ].join(' ')}
    >
      {children}
    </tr>
  )
}

export function Td({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <td className={`px-4 py-3 text-gray-700 ${className}`}>{children}</td>
  )
}
