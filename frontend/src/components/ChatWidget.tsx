import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { streamText } from '../utils/streamFetch'
import { renderLiteMarkdown } from '../utils/markdownLite'
import { IconHistory, IconPlus, IconSparkles, IconX, Logo } from './Icons'
import { useTerminalPanel } from '../terminal/TerminalPanelContext'
import { useChatPanel } from '../chat/ChatPanelContext'
import { ChatHistoryList } from '../chat/ChatHistoryList'
import { deleteChatSession, fetchTranscript, renameChatSession, useChatSessions, useRefreshChatSessions } from '../chat/useChatSessions'
import { useRightReserve } from '../layout/RightReserveContext'
import { AiFeedbackButtons } from './AiFeedbackButtons'
import { randomId } from '../utils/id'
import type { Cluster } from '../types/k8s'

interface Message {
  role: 'user' | 'assistant'
  content: string
  /** The persisted chat_messages id for anything loaded from history, and a
   *  client-generated stand-in for a turn that is still streaming (the server
   *  only writes the answer once it is complete, so there is no id to hand
   *  back mid-stream). Either way it gives AiFeedbackButtons a stable
   *  contextHash per answer. */
  id: string
}

export function ChatWidget() {
  const location = useLocation()
  const { username } = useAuth()
  const chatPanel = useChatPanel()
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  /** null = a new conversation that has not been saved yet. */
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
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

  // Current cluster from the URL. Off a /clusters/:id page there's no cluster
  // in view, so this can't default to the built-in cluster (id 0) the way
  // Sidebar's picker does when nothing else applies — id 0 is ADMIN-only, and
  // a USER on, say, Settings > Clusters would get a silent 403 from every
  // chat send. Fall back to whatever cluster this user can actually reach
  // instead (same query Sidebar uses, so no extra request).
  const clusterMatch = location.pathname.match(/^\/clusters\/(\d+)/)
  const { data: clusters } = useQuery<Cluster[]>({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get<Cluster[]>('/clusters')).data,
    staleTime: 30_000,
  })
  const clusterId = clusterMatch ? clusterMatch[1] : clusters?.[0] ? String(clusters[0].id) : null

  const sessionsQuery = useChatSessions(clusterId, chatPanel.isOpen)
  const refreshSessions = useRefreshChatSessions(clusterId)

  const startNewChat = useCallback(() => {
    setMessages([])
    setSessionId(null)
    setInput('')
    setHistoryOpen(false)
    setLoadError(null)
  }, [])

  // A session belongs to the cluster it was started against — the server serves
  // it under that cluster's URL and nowhere else. Following the user to another
  // cluster with a stale session id would just 404 on their next message, so
  // switching clusters starts a fresh conversation instead.
  useEffect(() => {
    startNewChat()
  }, [clusterId, startNewChat])

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

  const openSession = async (id: string) => {
    if (!clusterId) return
    setHistoryOpen(false)
    setLoadError(null)
    try {
      const transcript = await fetchTranscript(clusterId, id)
      setMessages(transcript.map((m) => ({ id: m.id, role: m.role, content: m.content })))
      setSessionId(id)
    } catch {
      setLoadError('Could not open that chat.')
    }
  }

  const renameSession = async (id: string, title: string) => {
    if (!clusterId) return
    try {
      await renameChatSession(clusterId, id, title)
      refreshSessions()
    } catch {
      setLoadError('Could not rename that chat.')
    }
  }

  const removeSession = async (id: string) => {
    if (!clusterId) return
    try {
      await deleteChatSession(clusterId, id)
      // Deleting the conversation on screen leaves nothing to continue.
      if (id === sessionId) startNewChat()
      refreshSessions()
    } catch {
      setLoadError('Could not delete that chat.')
    }
  }

  const send = async () => {
    const text = input.trim()
    if (!text || streaming || !clusterId) return

    setMessages((prev) => [
      ...prev,
      { role: 'user', content: text, id: randomId() },
      { role: 'assistant', content: '', id: randomId() },
    ])
    setInput('')
    setStreaming(true)

    try {
      // Only the new question goes over the wire — the server reads the rest of
      // the conversation back from the session it owns.
      const payload = { sessionId, message: text }
      await streamText(
        `/api/clusters/${clusterId}/chat`,
        payload,
        (chunk) => {
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = {
              ...next[next.length - 1],
              content: next[next.length - 1].content + chunk,
            }
            return next
          })
        },
        {
          // Both ids arrive before the first token. The session id is what turns
          // the next message into a continuation instead of a second orphan
          // conversation; the message id is the row this answer will be stored
          // under, so a rating filed on the bubble below points at real content
          // rather than a throwaway client id.
          onResponse: (res) => {
            const id = res.headers.get('X-Chat-Session-Id')
            if (id) setSessionId(id)
            const messageId = res.headers.get('X-Chat-Message-Id')
            if (messageId) {
              setMessages((prev) => {
                const next = [...prev]
                next[next.length - 1] = { ...next[next.length - 1], id: messageId }
                return next
              })
            }
          },
        },
      )
    } catch (e) {
      setMessages((prev) => {
        const next = [...prev]
        next[next.length - 1] = {
          ...next[next.length - 1],
          content: `[${e instanceof Error ? e.message : 'Something went wrong'}]`,
        }
        return next
      })
    } finally {
      setStreaming(false)
      // The title of a new session, and the ordering of an existing one, only
      // settle once the turn is written — so refresh the list after, not during.
      refreshSessions()
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
          <IconSparkles className="w-5 h-5" />
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
        className={`fixed top-0 right-0 bottom-0 z-[45] w-full sm:w-[var(--chat-width)] bg-white dark:bg-neutral-900
                    border-l border-gray-200 dark:border-neutral-700 shadow-2xl flex flex-col transform ease-in-out
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
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-neutral-700 shrink-0">
          <div className="flex items-center gap-2 min-w-0">
            <Logo className="w-5 h-5 shrink-0" />
            <span className="text-sm font-semibold text-gray-900 dark:text-neutral-100 truncate">
              {historyOpen ? 'Chat history' : 'KubeMind Assistant'}
            </span>
          </div>
          <div className="flex items-center gap-0.5 shrink-0">
            {!historyOpen && messages.length > 0 && (
              <button
                onClick={startNewChat}
                title="New chat"
                aria-label="New chat"
                className="p-1.5 rounded-md text-gray-400 dark:text-neutral-500 hover:text-gray-600 dark:hover:text-neutral-300 hover:bg-gray-100 dark:hover:bg-neutral-700 transition-colors"
              >
                <IconPlus className="w-4 h-4" />
              </button>
            )}
            <button
              onClick={() => setHistoryOpen((o) => !o)}
              title={historyOpen ? 'Back to chat' : 'Chat history'}
              aria-label={historyOpen ? 'Back to chat' : 'Chat history'}
              className={`p-1.5 rounded-md transition-colors ${
                historyOpen
                  ? 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-500/10'
                  : 'text-gray-400 dark:text-neutral-500 hover:text-gray-600 dark:hover:text-neutral-300 hover:bg-gray-100 dark:hover:bg-neutral-700'
              }`}
            >
              <IconHistory className="w-4 h-4" />
            </button>
            <button
              onClick={chatPanel.close}
              className="p-1.5 rounded-md text-gray-400 dark:text-neutral-500 hover:text-gray-600 dark:hover:text-neutral-300 hover:bg-gray-100 dark:hover:bg-neutral-700 transition-colors"
              aria-label="Close"
            >
              <IconX className="w-4 h-4" />
            </button>
          </div>
        </div>

        {loadError && (
          <p className="px-4 py-2 text-xs text-red-600 dark:text-red-400 border-b border-gray-200 dark:border-neutral-700">
            {loadError}
          </p>
        )}

        {historyOpen ? (
          <ChatHistoryList
            sessions={sessionsQuery.data}
            isLoading={sessionsQuery.isLoading}
            isError={sessionsQuery.isError}
            activeSessionId={sessionId}
            onSelect={openSession}
            onDelete={removeSession}
            onRename={renameSession}
            onNewChat={startNewChat}
          />
        ) : (
        <>
        {/* Messages */}
        <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
          {messages.length === 0 && (
            <div className="text-sm text-gray-400 dark:text-neutral-500 space-y-2">
              <p>Ask about this cluster or Kubernetes in general.</p>
              <ul className="space-y-1 text-xs">
                <li>• "Which pods are unhealthy right now?"</li>
                <li>• "Why would a pod be in CrashLoopBackOff?"</li>
                <li>• "How do I roll back a deployment?"</li>
              </ul>
            </div>
          )}
          {messages.map((m, i) => (
            <div key={m.id} className={m.role === 'user' ? 'flex justify-end' : 'flex flex-col items-start'}>
              <div
                className={`max-w-[85%] rounded-lg px-3 py-2 text-sm leading-relaxed ${
                  m.role === 'user'
                    ? 'bg-blue-600 text-white whitespace-pre-wrap'
                    : 'bg-gray-100 dark:bg-neutral-700 text-gray-800 dark:text-neutral-200'
                }`}
              >
                {m.role === 'assistant'
                  ? (m.content ? renderLiteMarkdown(m.content) : (streaming ? '…' : ''))
                  : m.content}
              </div>
              {m.role === 'assistant' && m.content && clusterId
                && !(streaming && i === messages.length - 1) && (
                <div className="mt-1 pl-1">
                  <AiFeedbackButtons clusterId={clusterId} surface="CHAT" contextHash={m.id} />
                </div>
              )}
            </div>
          ))}
        </div>

        {/* Composer */}
        <div className="border-t border-gray-200 dark:border-neutral-700 p-3 shrink-0">
          <div className="flex items-end gap-2">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              rows={1}
              disabled={!clusterId}
              placeholder={clusterId ? 'Ask anything…' : 'Register a cluster first to chat'}
              className="flex-1 resize-none rounded-md border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                         text-gray-900 dark:text-neutral-100 placeholder:text-gray-400 dark:placeholder:text-neutral-500 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                         max-h-24 disabled:opacity-60 disabled:cursor-not-allowed"
            />
            <button
              onClick={send}
              disabled={streaming || !input.trim() || !clusterId}
              className="shrink-0 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white
                         hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {streaming ? '…' : 'Send'}
            </button>
          </div>
          <p className="mt-1.5 text-[10px] text-gray-400 dark:text-neutral-500">AI-generated — verify before acting.</p>
        </div>
        </>
        )}
      </div>
    </>
  )
}
