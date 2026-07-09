import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

interface ChatPanelContextValue {
  isOpen: boolean
  width: number
  open: () => void
  close: () => void
  toggle: () => void
  setWidth: (w: number) => void
}

const ChatPanelContext = createContext<ChatPanelContextValue | null>(null)

const MIN_WIDTH = 340
const MAX_WIDTH = 720
const DEFAULT_WIDTH = 420
const STORAGE_KEY = 'kubemind.chatPanelWidth'

export function ChatPanelProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false)
  const [width, setWidthState] = useState(() => {
    const stored = Number(localStorage.getItem(STORAGE_KEY))
    return stored >= MIN_WIDTH && stored <= MAX_WIDTH ? stored : DEFAULT_WIDTH
  })

  const open = useCallback(() => setIsOpen(true), [])
  const close = useCallback(() => setIsOpen(false), [])
  const toggle = useCallback(() => setIsOpen((o) => !o), [])
  const setWidth = useCallback((w: number) => {
    const clamped = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, w))
    setWidthState(clamped)
    localStorage.setItem(STORAGE_KEY, String(clamped))
  }, [])

  return (
    <ChatPanelContext.Provider value={{ isOpen, width, open, close, toggle, setWidth }}>
      {children}
    </ChatPanelContext.Provider>
  )
}

export function useChatPanel() {
  const ctx = useContext(ChatPanelContext)
  if (!ctx) throw new Error('useChatPanel must be used within ChatPanelProvider')
  return ctx
}

export { MIN_WIDTH as CHAT_PANEL_MIN_WIDTH, MAX_WIDTH as CHAT_PANEL_MAX_WIDTH }
