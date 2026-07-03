import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { IconHelm, IconX } from './Icons'

interface Message {
  role: 'user' | 'assistant'
  content: string
}

/** Reads Spring's readable CSRF cookie so streaming fetch() calls pass CSRF. */
function csrfToken(): string {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return match ? decodeURIComponent(match.split('=')[1]) : ''
}

export function ChatWidget() {
  const location = useLocation()
  const { username } = useAuth()
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  // Current cluster from the URL (defaults to the built-in local cluster).
  const clusterMatch = location.pathname.match(/^\/clusters\/(\d+)/)
  const clusterId = clusterMatch ? clusterMatch[1] : '0'

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [messages, open])

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
      const res = await fetch(`/api/clusters/${clusterId}/chat`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
        body: JSON.stringify({ messages: history }),
      })
      if (!res.ok || !res.body) {
        throw new Error(res.status === 403 ? 'Not authorized' : `Request failed (${res.status})`)
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      // Stream tokens into the last (assistant) message.
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const chunk = decoder.decode(value, { stream: true })
        setMessages((prev) => {
          const next = [...prev]
          next[next.length - 1] = {
            role: 'assistant',
            content: next[next.length - 1].content + chunk,
          }
          return next
        })
      }
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
      {/* Launcher */}
      {!open && (
        <button
          onClick={() => setOpen(true)}
          className="fixed bottom-5 right-5 z-40 flex items-center gap-2 rounded-full bg-blue-600 px-4 py-3
                     text-sm font-medium text-white shadow-lg hover:bg-blue-700 transition-colors"
          aria-label="Open AI assistant"
        >
          <IconHelm className="w-4 h-4" />
          Ask AI
        </button>
      )}

      {/* Panel */}
      {open && (
        <div className="fixed bottom-5 right-5 z-40 flex flex-col w-[calc(100vw-2.5rem)] sm:w-96 h-[32rem]
                        rounded-xl border border-gray-200 bg-white shadow-2xl">
          {/* Header */}
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 shrink-0">
            <div className="flex items-center gap-2">
              <IconHelm className="w-4 h-4 text-blue-600" />
              <span className="text-sm font-semibold text-gray-900">KubeMind Assistant</span>
            </div>
            <button
              onClick={() => setOpen(false)}
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
                  className={`max-w-[85%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap leading-relaxed ${
                    m.role === 'user'
                      ? 'bg-blue-600 text-white'
                      : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {m.content || (streaming ? '…' : '')}
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
      )}
    </>
  )
}
