import type { ReactNode } from 'react'
import { useEffect } from 'react'
import { IconX } from './Icons'

interface Props {
  open: boolean
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
}

export function DetailDrawer({ open, title, subtitle, onClose, children }: Props) {
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open, onClose])

  return (
    <>
      {/* Backdrop */}
      <div
        aria-hidden="true"
        className={`fixed inset-0 bg-black/30 z-20 transition-opacity duration-200
                    ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
        onClick={onClose}
      />

      {/* Panel */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`fixed top-0 right-0 h-full w-full sm:w-[520px] bg-white z-30 shadow-xl
                    flex flex-col transform transition-transform duration-200 ease-in-out
                    ${open ? 'translate-x-0' : 'translate-x-full'}`}
      >
        {/* Header */}
        <div className="flex items-start justify-between gap-4 px-6 py-4 border-b border-gray-200 shrink-0">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-gray-900 truncate">{title}</h2>
            {subtitle && <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            className="shrink-0 p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            aria-label="Close"
          >
            <IconX className="w-4 h-4" />
          </button>
        </div>

        {/* Scrollable body */}
        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-1">
          {children}
        </div>
      </div>
    </>
  )
}

export function DrawerSection({ title }: { title: string }) {
  return (
    <p className="pt-4 pb-1 text-[10px] font-semibold text-gray-400 uppercase tracking-widest first:pt-0">
      {title}
    </p>
  )
}

export function DrawerRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex gap-3 py-1.5 border-b border-gray-50 last:border-0">
      <span className="w-32 shrink-0 text-xs text-gray-400 pt-px">{label}</span>
      <span className="text-sm text-gray-800 break-all min-w-0">{value ?? <span className="text-gray-300">—</span>}</span>
    </div>
  )
}
