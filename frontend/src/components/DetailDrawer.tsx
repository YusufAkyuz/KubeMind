import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { IconX } from './Icons'

interface Props {
  open: boolean
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
}

const MIN_WIDTH = 380
const MAX_WIDTH = 1000
const DEFAULT_WIDTH = 520
const STORAGE_KEY = 'kubemind.drawerWidth'

export function DetailDrawer({ open, title, subtitle, onClose, children }: Props) {
  const [width, setWidth] = useState(() => {
    const stored = Number(localStorage.getItem(STORAGE_KEY))
    return stored >= MIN_WIDTH && stored <= MAX_WIDTH ? stored : DEFAULT_WIDTH
  })
  const [resizing, setResizing] = useState(false)
  const startRef = useRef<{ x: number; width: number } | null>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open, onClose])

  useEffect(() => {
    if (!resizing) return

    const onMove = (e: MouseEvent) => {
      if (!startRef.current) return
      // Dragging left (negative deltaX) grows the drawer, since it's anchored right.
      const delta = startRef.current.x - e.clientX
      const next = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startRef.current.width + delta))
      setWidth(next)
    }
    const onUp = () => {
      setResizing(false)
      setWidth((w) => { localStorage.setItem(STORAGE_KEY, String(w)); return w })
    }

    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    return () => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
  }, [resizing])

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    startRef.current = { x: e.clientX, width }
    setResizing(true)
  }

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
        style={{ '--drawer-width': `${width}px` } as React.CSSProperties}
        className={`fixed top-0 right-0 h-full w-full sm:w-[var(--drawer-width)] bg-white z-30 shadow-xl
                    flex flex-col transform ease-in-out
                    ${resizing ? '' : 'transition-transform duration-200'}
                    ${open ? 'translate-x-0' : 'translate-x-full'}`}
      >
        {/* Resize handle — drag left/right to resize (desktop only) */}
        <div
          onMouseDown={startResize}
          className="hidden sm:block absolute left-0 top-0 h-full w-1.5 -translate-x-1/2 cursor-col-resize
                     group z-10"
          title="Drag to resize"
        >
          <div className="h-full w-full group-hover:bg-blue-400/60 transition-colors" />
        </div>

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
