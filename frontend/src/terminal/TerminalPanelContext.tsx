import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

export type TerminalSession =
  | { id: string; type: 'pod'; clusterId: string; ns: string; pod: string; containers: string[]; container: string }
  | { id: string; type: 'node'; clusterId: string; nodeName: string }
  | { id: string; type: 'cluster'; clusterId: string }

function podSessionId(clusterId: string, ns: string, pod: string) {
  return `pod:${clusterId}:${ns}:${pod}`
}
function nodeSessionId(clusterId: string, nodeName: string) {
  return `node:${clusterId}:${nodeName}`
}
function clusterSessionId(clusterId: string) {
  return `cluster:${clusterId}`
}

interface TerminalPanelContextValue {
  sessions: TerminalSession[]
  activeId: string | null
  isMinimized: boolean
  height: number
  openPodExec: (clusterId: string, ns: string, pod: string, containers: string[], container?: string) => void
  openNodeExec: (clusterId: string, nodeName: string) => void
  openClusterTerminal: (clusterId: string) => void
  setContainer: (id: string, container: string) => void
  activate: (id: string) => void
  closeSession: (id: string) => void
  toggleMinimize: () => void
  setHeight: (h: number) => void
}

const TerminalPanelContext = createContext<TerminalPanelContextValue | null>(null)

const DEFAULT_HEIGHT = 360
const MIN_HEIGHT = 180
const STORAGE_KEY = 'kubemind.terminalPanelHeight'

/**
 * Global, App-root-mounted state for the bottom-docked terminal panel — a
 * Chrome-like tab strip of pod exec / node shell / cluster terminal sessions.
 * Deliberately lives above <Routes> (see TerminalPanel.tsx) so every tab's
 * WebSocket survives page navigation, not just the active one.
 */
export function TerminalPanelProvider({ children }: { children: ReactNode }) {
  const [sessions, setSessions] = useState<TerminalSession[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [isMinimized, setIsMinimized] = useState(false)
  const [height, setHeightState] = useState(() => {
    const stored = Number(localStorage.getItem(STORAGE_KEY))
    return stored && stored >= MIN_HEIGHT ? stored : DEFAULT_HEIGHT
  })

  /** Adds the session if it's not already open (by id), then activates it either way. */
  const openOrActivate = useCallback((session: TerminalSession) => {
    setSessions((prev) => (prev.some((s) => s.id === session.id) ? prev : [...prev, session]))
    setActiveId(session.id)
    setIsMinimized(false)
  }, [])

  const openPodExec = useCallback((clusterId: string, ns: string, pod: string, containers: string[], container?: string) => {
    openOrActivate({
      id: podSessionId(clusterId, ns, pod), type: 'pod', clusterId, ns, pod,
      containers, container: container || containers[0] || '',
    })
  }, [openOrActivate])

  const openNodeExec = useCallback((clusterId: string, nodeName: string) => {
    openOrActivate({ id: nodeSessionId(clusterId, nodeName), type: 'node', clusterId, nodeName })
  }, [openOrActivate])

  const openClusterTerminal = useCallback((clusterId: string) => {
    openOrActivate({ id: clusterSessionId(clusterId), type: 'cluster', clusterId })
  }, [openOrActivate])

  const setContainer = useCallback((id: string, container: string) => {
    setSessions((prev) => prev.map((s) => (s.id === id && s.type === 'pod' ? { ...s, container } : s)))
  }, [])

  const activate = useCallback((id: string) => {
    setActiveId(id)
    setIsMinimized(false)
  }, [])

  const closeSession = useCallback((id: string) => {
    setSessions((prev) => {
      const next = prev.filter((s) => s.id !== id)
      setActiveId((current) => {
        if (current !== id) return current
        return next.length > 0 ? next[next.length - 1].id : null
      })
      return next
    })
  }, [])

  const toggleMinimize = useCallback(() => setIsMinimized((m) => !m), [])

  const setHeight = useCallback((h: number) => {
    const clamped = Math.max(MIN_HEIGHT, Math.min(h, Math.round(window.innerHeight * 0.85)))
    setHeightState(clamped)
    localStorage.setItem(STORAGE_KEY, String(clamped))
  }, [])

  return (
    <TerminalPanelContext.Provider value={{
      sessions, activeId, isMinimized, height,
      openPodExec, openNodeExec, openClusterTerminal, setContainer, activate, closeSession, toggleMinimize, setHeight,
    }}>
      {children}
    </TerminalPanelContext.Provider>
  )
}

export function useTerminalPanel() {
  const ctx = useContext(TerminalPanelContext)
  if (!ctx) throw new Error('useTerminalPanel must be used within TerminalPanelProvider')
  return ctx
}
