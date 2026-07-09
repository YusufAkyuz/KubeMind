import { useEffect, useRef, useState } from 'react'
import type { Dispatch, MouseEvent as ReactMouseEvent, SetStateAction } from 'react'
import { useTerminalPanel } from './TerminalPanelContext'
import type { TerminalSession } from './TerminalPanelContext'
import { useChatPanel } from '../chat/ChatPanelContext'
import { useWsTerminal } from '../hooks/useWsTerminal'
import type { TerminalConnState } from '../hooks/useWsTerminal'
import { IconChevronDown, IconTerminal, IconX } from '../components/Icons'

function buildWsUrl(session: TerminalSession): string {
  if (session.type === 'pod') {
    return `/ws/exec?clusterId=${session.clusterId}&ns=${encodeURIComponent(session.ns)}`
      + `&pod=${encodeURIComponent(session.pod)}&container=${encodeURIComponent(session.container)}`
  }
  if (session.type === 'node') {
    return `/ws/exec-node?clusterId=${session.clusterId}&node=${encodeURIComponent(session.nodeName)}`
  }
  return `/ws/exec-cluster?clusterId=${session.clusterId}`
}

function tabLabel(session: TerminalSession): string {
  if (session.type === 'pod') return session.pod
  if (session.type === 'node') return session.nodeName
  return 'Cluster Terminal'
}

function riskMessage(session: TerminalSession): string | null {
  if (session.type === 'node') return `Privileged shell with full host access to ${session.nodeName}.`
  if (session.type === 'cluster') return 'Grants cluster-admin for this session — commands aren’t individually audited.'
  return null
}

/**
 * Bottom-docked, IDE-style terminal panel with a Chrome-like tab strip — each
 * tab is an independent pod/node/cluster session. Mounted once at the App root
 * (see App.tsx), not inside any page's <Layout>: that's what lets every tab's
 * xterm/WebSocket instance survive navigating to another page, and keeps every
 * background tab's connection alive while you're looking at a different one.
 */
export function TerminalPanel() {
  const { sessions, activeId, isMinimized, height, activate, closeSession, toggleMinimize, setHeight, setContainer } = useTerminalPanel()
  const dragState = useRef<{ startY: number; startHeight: number } | null>(null)
  const [connStates, setConnStates] = useState<Record<string, TerminalConnState>>({})
  // The AI chat panel (see chat/ChatPanelContext.tsx) is anchored right at a higher
  // z-index — reserve its width so this dock sits beside it instead of underneath it.
  const chatPanel = useChatPanel()
  const reservedRight = chatPanel.isOpen ? chatPanel.width : 0

  if (sessions.length === 0) return null

  const activeSession = sessions.find((s) => s.id === activeId) ?? sessions[0]

  const onDragStart = (e: ReactMouseEvent) => {
    dragState.current = { startY: e.clientY, startHeight: height }
    const onMove = (ev: MouseEvent) => {
      if (!dragState.current) return
      setHeight(dragState.current.startHeight + (dragState.current.startY - ev.clientY))
    }
    const onUp = () => {
      dragState.current = null
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const activeConnState = connStates[activeSession.id]

  return (
    <div
      className="fixed bottom-0 left-0 lg:left-64 z-40 flex flex-col bg-gray-950 border-t border-gray-800 shadow-[0_-4px_24px_rgba(0,0,0,0.3)]"
      style={{ height: isMinimized ? 40 : height, right: reservedRight }}
    >
      {!isMinimized && (
        <div
          onMouseDown={onDragStart}
          className="h-1.5 shrink-0 cursor-row-resize hover:bg-blue-600/60 transition-colors"
          title="Drag to resize"
        />
      )}

      {/* Tab strip */}
      <div className="flex items-stretch h-10 border-b border-gray-800 bg-gray-900 shrink-0 overflow-x-auto">
        {sessions.map((s) => (
          <button
            key={s.id}
            onClick={() => activate(s.id)}
            className={`group flex items-center gap-2 px-3 border-r border-gray-800 text-xs shrink-0 max-w-[180px] transition-colors ${
              s.id === activeSession.id ? 'bg-gray-950 text-gray-200' : 'text-gray-500 hover:bg-gray-800/60 hover:text-gray-300'
            }`}
          >
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${
              connStates[s.id] === 'connected' ? 'bg-emerald-400'
              : connStates[s.id] === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-600'}`} />
            <span className="truncate">{tabLabel(s)}</span>
            <span
              role="button"
              tabIndex={-1}
              onClick={(e) => { e.stopPropagation(); closeSession(s.id) }}
              className="shrink-0 p-0.5 rounded text-gray-600 opacity-0 group-hover:opacity-100 hover:text-gray-200 hover:bg-gray-700 transition-opacity"
              title="Close"
            >
              <IconX className="w-3 h-3" />
            </span>
          </button>
        ))}

        <div className="ml-auto flex items-center gap-3 px-3 shrink-0">
          {activeConnState && (
            <span className="text-[10px] text-gray-500 uppercase tracking-wide">
              {activeConnState === 'connected' ? 'Connected' : activeConnState === 'connecting' ? 'Connecting…' : 'Closed'}
            </span>
          )}
          <button
            onClick={toggleMinimize}
            title={isMinimized ? 'Restore' : 'Minimize'}
            className="p-1 rounded text-gray-500 hover:text-gray-200 hover:bg-gray-800 transition-colors"
          >
            <IconChevronDown className={`w-3.5 h-3.5 transition-transform ${isMinimized ? 'rotate-180' : ''}`} />
          </button>
        </div>
      </div>

      {/* One mounted view per session — hidden (not unmounted) when inactive, so
          every tab keeps its own live terminal/WebSocket in the background. */}
      {!isMinimized && sessions.map((s) => (
        <TerminalSessionView
          key={s.id}
          session={s}
          active={s.id === activeSession.id}
          setConnStates={setConnStates}
          setContainer={setContainer}
        />
      ))}
    </div>
  )
}

function TerminalSessionView({ session, active, setConnStates, setContainer }: {
  session: TerminalSession
  active: boolean
  // Both setters are referentially stable across renders (a useState setter, and a
  // useCallback-wrapped context method) — that stability is what makes the effect below
  // safe. Passing a freshly-created inline closure here instead (as this used to) reruns
  // the effect on every render and loops forever ("Maximum update depth exceeded").
  setConnStates: Dispatch<SetStateAction<Record<string, TerminalConnState>>>
  setContainer: (id: string, container: string) => void
}) {
  const [confirmed, setConfirmed] = useState(false)
  const requiresConfirm = session.type !== 'pod'
  const connected = !requiresConfirm || confirmed
  const wsUrl = connected ? buildWsUrl(session) : null
  const { termElRef, connState } = useWsTerminal(wsUrl, connected)

  useEffect(() => {
    setConnStates((prev) => ({ ...prev, [session.id]: connState }))
  }, [connState, session.id, setConnStates])
  // A closed connection reported before the user ever confirmed shouldn't happen,
  // but if the session is reset (e.g. reopened), require confirming again.
  useEffect(() => { setConfirmed(false) }, [session.id])

  if (!connected) {
    return (
      <div className={active ? 'flex-1 min-h-0 flex flex-col' : 'hidden'}>
        <div className="flex-1 min-h-0 flex items-center justify-center px-6">
          <div className="max-w-md text-center space-y-3">
            <p className="text-sm text-amber-300">{riskMessage(session)}</p>
            <button
              onClick={() => setConfirmed(true)}
              className="rounded-md bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-700 transition-colors"
            >
              I understand the risk — Connect
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className={active ? 'flex-1 min-h-0 flex flex-col' : 'hidden'}>
      {session.type === 'pod' && session.containers.length > 1 && (
        <div className="flex items-center gap-2 px-3 py-1.5 border-b border-gray-800 bg-gray-900 shrink-0">
          <IconTerminal className="w-3 h-3 text-gray-600 shrink-0" />
          <span className="text-[11px] text-gray-500">{session.ns}/{session.pod}</span>
          <select
            value={session.container}
            onChange={(e) => setContainer(session.id, e.target.value)}
            className="ml-auto h-6 rounded-md border border-gray-700 bg-gray-800 px-1.5 text-xs text-gray-200
                       focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {session.containers.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
      )}
      <div ref={termElRef} className="flex-1 min-h-0 overflow-hidden p-2" />
    </div>
  )
}
