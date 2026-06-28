import { useState } from 'react'
import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'
import { IconMenu, IconHelm } from './Icons'

interface Props {
  children: ReactNode
}

export function Layout({ children }: Props) {
  const [open, setOpen] = useState(false)

  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      {/* ── Desktop sidebar ───────────────────────────────────────────────── */}
      <div className="hidden lg:block shrink-0">
        <Sidebar />
      </div>

      {/* ── Mobile sidebar ────────────────────────────────────────────────── */}
      {/* Backdrop */}
      <div
        aria-hidden="true"
        className={`fixed inset-0 z-20 bg-black/40 lg:hidden transition-opacity duration-200
                    ${open ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
        onClick={() => setOpen(false)}
      />
      {/* Drawer */}
      <div
        className={`fixed inset-y-0 left-0 z-30 lg:hidden transform transition-transform duration-200 ease-in-out
                    ${open ? 'translate-x-0' : '-translate-x-full'}`}
      >
        <Sidebar onClose={() => setOpen(false)} />
      </div>

      {/* ── Content area ──────────────────────────────────────────────────── */}
      <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
        {/* Mobile top bar */}
        <header className="lg:hidden flex items-center gap-3 h-14 px-4 bg-white border-b border-gray-200 shrink-0">
          <button
            onClick={() => setOpen(true)}
            className="p-1.5 rounded-md text-gray-500 hover:bg-gray-100 hover:text-gray-700 transition-colors"
            aria-label="Open navigation"
          >
            <IconMenu />
          </button>
          <div className="flex items-center gap-2">
            <IconHelm className="w-5 h-5 text-blue-600" />
            <span className="text-[15px] font-semibold tracking-tight text-gray-900">KubeMind</span>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto">
          <div className="px-4 py-6 sm:px-6 lg:px-8 max-w-screen-2xl mx-auto">
            {children}
          </div>
        </main>
      </div>
    </div>
  )
}
