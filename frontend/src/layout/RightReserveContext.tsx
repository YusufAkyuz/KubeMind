import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

interface RightReserveContextValue {
  /** Registers how many px a right-anchored panel (chat, drawer, ...) currently
   *  occupies, keyed by a stable id. Pass 0 to release. Other right-anchored
   *  overlays (main content, the terminal dock) read `total` — the widest
   *  concurrently-open panel — to shrink themselves instead of running underneath. */
  register: (id: string, widthPx: number) => void
  total: number
}

const RightReserveContext = createContext<RightReserveContextValue | null>(null)

export function RightReserveProvider({ children }: { children: ReactNode }) {
  const [reservations, setReservations] = useState<Record<string, number>>({})

  const register = useCallback((id: string, widthPx: number) => {
    setReservations((prev) => {
      if (widthPx <= 0) {
        if (!(id in prev)) return prev
        const next = { ...prev }
        delete next[id]
        return next
      }
      if (prev[id] === widthPx) return prev
      return { ...prev, [id]: widthPx }
    })
  }, [])

  const total = useMemo(() => Object.values(reservations).reduce((max, w) => Math.max(max, w), 0), [reservations])

  const value = useMemo(() => ({ register, total }), [register, total])

  return <RightReserveContext.Provider value={value}>{children}</RightReserveContext.Provider>
}

export function useRightReserve() {
  const ctx = useContext(RightReserveContext)
  if (!ctx) throw new Error('useRightReserve must be used within RightReserveProvider')
  return ctx
}
