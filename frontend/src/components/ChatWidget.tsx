import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { streamText } from '../utils/streamFetch'
import { renderLiteMarkdown } from '../utils/markdownLite'
import { IconHelm, IconX, Logo } from './Icons'
import { useTerminalPanel } from '../terminal/TerminalPanelContext'
import { useChatPanel } from '../chat/ChatPanelContext'
import { useRightReserve } from '../layout/RightReserveContext'

interface Message {
  role: 'user' | 'assistant'
  content: string
}

export function ChatWidget() {
  const location = useLocation()
  const { username } = useAuth()
  const chatPanel = useChatPanel()
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const terminalPanel = useTerminalPanel()
  // The bottom-docked terminal (see terminal/TerminalPanel.tsx) sits on top of everything —
  // without this offset the launcher/panel renders underneath it instead of above it.
  const terminalReserved = terminalPanel.sessions.length > 0
    ? (terminalPanel.isMinimized ? 40 : terminalPanel.height) : 0

  const [resizing, setResizing] = useState(false)
  const startRef = useRef<{ x: number; width: number } | null>(null)

  // Registers this panel's current footprint so other right-anchored overlays
  // (the terminal dock, the page's main content area) shrink to make room
  // instead of running underneath it. See layout/RightReserveContext.tsx.
  const rightReserve = useRightReserve()
  useEffect(() => {
    rightReserve.register('chat', chatPanel.isOpen ? chatPanel.width : 0)
    return () => rightReserve.register('chat', 0)
  }, [chatPanel.isOpen, chatPanel.width, rightReserve])

  // Current cluster from the URL (defaults to the built-in local cluster).
  const clusterMatch = location.pathname.match(/^\/clusters\/(\d+)/)
  const clusterId = clusterMatch ? clusterMatch[1] : '0'

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [messages, chatPanel.isOpen])

  useEffect(() => {
    if (!chatPanel.isOpen) return
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') chatPanel.close() }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [chatPanel])

  useEffect(() => {
    if (!resizing) return
    const onMove = (e: MouseEvent) => {
      if (!startRef.current) return
      // Dragging left (negative deltaX) grows the panel, since it's anchored right.
      const delta = startRef.current.x - e.clientX
      chatPanel.setWidth(startRef.current.width + delta)
    }
    const onUp = () => setResizing(false)
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
  }, [resizing, chatPanel])

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    startRef.current = { x: e.clientX, width: chatPanel.width }
    setResizing(true)
  }

  // Only for authenticated users
  if (!username || location.pathname === '/login') return null

  const send = async () => {
    const text = input.trim()
    if (!text || streaming) return

    const history: Message[] = [...messages, { role: 'user', content: text }]
    setMessages([...history, { role: 'assistant', content: '' }])
    setInput('')
    setStreaming(true)

    try {
      await streamText(`/api/clusters/${clusterId}/chat`, { messages: history }, (chunk) => {
        setMessages((prev) => {
          const next = [...prev]
          next[next.length - 1] = {
            role: 'assistant',
            content: next[next.length - 1].content + chunk,
          }
          return next
        })
      })
    } catch (e) {
      setMessages((prev) => {
        const next = [...prev]
        next[next.length - 1] = {
          role: 'assistant',
          content: `[${e instanceof Error ? e.message : 'Something went wrong'}]`,
        }
        return next
      })
    } finally {
      setStreaming(false)
    }
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  return (
    <>
      {/* Launcher — compact circle so it never crowds page controls */}
      {/* z-[25] sits above ordinary page content but below the detail drawer (z-30) — when
          a drawer is open it naturally covers this corner instead of overlapping it. */}
      {!chatPanel.isOpen && (
        <button
          onClick={chatPanel.open}
          title="Ask AI"
          style={{ bottom: 20 + terminalReserved }}
          className="fixed right-5 z-[25] flex items-center justify-center w-12 h-12 rounded-full
                     bg-gradient-to-br from-blue-500 to-violet-600 text-white shadow-lg
                     hover:shadow-xl hover:scale-105 transition-all"
          aria-label="Open AI assistant"
        >
          <IconHelm className="w-5 h-5" />
        </button>
      )}

      {/* Full-height sliding panel — anchored right, pushes page content aside via the
          spacer Layout.tsx reserves (chatPanel.width). Deliberately spans the full
          viewport height (z-[45], above the terminal dock's z-40) instead of stopping
          above the docked terminal — simpler and more predictable than trying to tile
          two independently-resizable right/bottom panels around each other. */}
      <div
        role="dialog"
        aria-modal="false"
        aria-label="AI assistant"
        aria-hidden={!chatPanel.isOpen}
        style={{ '--chat-width': `${chatPanel.width}px` } as React.CSSProperties}
        className={`fixed top-0 right-0 bottom-0 z-[45] w-full sm:w-[var(--chat-width)] bg-white
                    border-l border-gray-200 shadow-2xl flex flex-col transform ease-in-out
                    ${resizing ? '' : 'transition-transform duration-200'}
                    ${chatPanel.isOpen ? 'translate-x-0' : 'translate-x-full pointer-events-none'}`}
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
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 shrink-0">
          <div className="flex items-center gap-2">
            <Logo className="w-5 h-5" />
            <span className="text-sm font-semibold text-gray-900">KubeMind Assistant</span>
          </div>
          <button
            onClick={chatPanel.close}
            className="p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            aria-label="Close"
          >
            <IconX className="w-4 h-4" />
          </button>
        </div>

        {/* Messages */}
        <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
          {messages.length === 0 && (
            <div className="text-sm text-gray-400 space-y-2">
              <p>Ask about this cluster or Kubernetes in general.</p>
              <ul className="space-y-1 text-xs">
                <li>• "Which pods are unhealthy right now?"</li>
                <li>• "Why would a pod be in CrashLoopBackOff?"</li>
                <li>• "How do I roll back a deployment?"</li>
              </ul>
            </div>
          )}
          {messages.map((m, i) => (
            <div key={i} className={m.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
              <div
                className={`max-w-[85%] rounded-lg px-3 py-2 text-sm leading-relaxed ${
                  m.role === 'user'
                    ? 'bg-blue-600 text-white whitespace-pre-wrap'
                    : 'bg-gray-100 text-gray-800'
                }`}
              >
                {m.role === 'assistant'
                  ? (m.content ? renderLiteMarkdown(m.content) : (streaming ? '…' : ''))
                  : m.content}
              </div>
            </div>
          ))}
        </div>

        {/* Composer */}
        <div className="border-t border-gray-200 p-3 shrink-0">
          <div className="flex items-end gap-2">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              rows={1}
              placeholder="Ask anything…"
              className="flex-1 resize-none rounded-md border border-gray-300 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                         max-h-24"
            />
            <button
              onClick={send}
              disabled={streaming || !input.trim()}
              className="shrink-0 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {streaming ? '…' : 'Send'}
            </button>
          </div>
          <p className="mt-1.5 text-[10px] text-gray-400">AI-generated — verify before acting.</p>
        </div>
      </div>
    </>
  )
}
