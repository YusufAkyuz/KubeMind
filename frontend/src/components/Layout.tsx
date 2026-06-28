import { useState } from 'react'
import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'

interface Props {
  children: ReactNode
}

export function Layout({ children }: Props) {
  const [sidebarOpen, setSidebarOpen] = useState(false)

  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      {/* ── Desktop sidebar (always visible ≥ lg) ───────────────────────── */}
      <div className="hidden lg:flex shrink-0">
        <Sidebar />
      </div>

      {/* ── Mobile sidebar overlay ────────────────────────────────────────── */}
      <>
        {/* Backdrop */}
        <div
          className={`fixed inset-0 bg-black/40 z-20 lg:hidden transition-opacity duration-200 ${
            sidebarOpen ? 'opacity-100' : 'opacity-0 pointer-events-none'
          }`}
          onClick={() => setSidebarOpen(false)}
        />
        {/* Drawer */}
        <div
          className={`fixed top-0 left-0 h-full z-30 lg:hidden transform transition-transform duration-200 ${
            sidebarOpen ? 'translate-x-0' : '-translate-x-full'
          }`}
        >
          <Sidebar onClose={() => setSidebarOpen(false)} />
        </div>
      </>

      {/* ── Main area ─────────────────────────────────────────────────────── */}
      <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
        {/* Mobile top bar */}
        <header className="lg:hidden flex items-center gap-3 px-4 py-3 bg-white border-b">
          <button
            onClick={() => setSidebarOpen(true)}
            className="text-gray-600 hover:text-gray-900 text-xl leading-none"
            aria-label="Open menu"
          >
            ☰
          </button>
          <div className="flex items-center gap-2">
            <span className="text-blue-600">⎈</span>
            <span className="font-bold text-gray-800">KubeMind</span>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
