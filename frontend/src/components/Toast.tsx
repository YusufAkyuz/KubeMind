import { createContext, useCallback, useContext, useRef, useState } from 'react'
import type { ReactNode } from 'react'

interface Toast {
  id: number
  kind: 'success' | 'error'
  message: string
}

interface ToastApi {
  success: (message: string) => void
  error: (message: string) => void
}

const ToastContext = createContext<ToastApi | undefined>(undefined)

const TOAST_TTL_MS = 4_000

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const seq = useRef(0)

  const push = useCallback((kind: Toast['kind'], message: string) => {
    const id = ++seq.current
    setToasts((prev) => [...prev, { id, kind, message }])
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), TOAST_TTL_MS)
  }, [])

  const apiRef = useRef<ToastApi>({
    success: (m) => push('success', m),
    error: (m) => push('error', m),
  })

  return (
    <ToastContext.Provider value={apiRef.current}>
      {children}
      {/* Container */}
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex flex-col gap-2 w-full max-w-sm px-4">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={`rounded-lg px-4 py-3 text-sm shadow-lg border
                        ${t.kind === 'success'
                          ? 'bg-white border-emerald-200 text-emerald-800'
                          : 'bg-white border-red-200 text-red-700'}`}
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) {
    throw new Error('useToast must be used within a ToastProvider')
  }
  return ctx
}
