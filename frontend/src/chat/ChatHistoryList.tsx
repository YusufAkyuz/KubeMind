import { IconPlus, IconTrash } from '../components/Icons'
import { formatAge } from '../utils/format'
import type { ChatSessionSummary } from './useChatSessions'

interface Props {
  sessions: ChatSessionSummary[] | undefined
  isLoading: boolean
  isError: boolean
  activeSessionId: string | null
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onNewChat: () => void
}

/**
 * The saved-conversation list, shown in place of the transcript inside the chat
 * panel. Deliberately an overlay within the panel rather than a second column:
 * the panel is resizable down to 340px, where a sidebar would leave no room for
 * the conversation itself.
 */
export function ChatHistoryList({
  sessions, isLoading, isError, activeSessionId, onSelect, onDelete, onNewChat,
}: Props) {
  return (
    <div className="flex-1 overflow-y-auto px-2 py-2">
      <button
        onClick={onNewChat}
        className="w-full flex items-center gap-2 rounded-md px-2.5 py-2 text-sm font-medium
                   text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-500/10 transition-colors"
      >
        <IconPlus className="w-4 h-4" />
        New chat
      </button>

      {isLoading && <p className="px-2.5 py-2 text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && (
        <p className="px-2.5 py-2 text-sm text-red-600 dark:text-red-400">Could not load your chat history.</p>
      )}
      {sessions && sessions.length === 0 && !isLoading && (
        <p className="px-2.5 py-6 text-sm text-gray-400 dark:text-neutral-500 text-center">
          No saved chats yet for this cluster.
        </p>
      )}

      {sessions?.map((s) => (
        <div
          key={s.id}
          className={`group flex items-center gap-1 rounded-md transition-colors ${
            s.id === activeSessionId
              ? 'bg-gray-100 dark:bg-neutral-800'
              : 'hover:bg-gray-50 dark:hover:bg-neutral-800/60'
          }`}
        >
          <button
            onClick={() => onSelect(s.id)}
            className="flex-1 min-w-0 text-left px-2.5 py-2"
          >
            <p className="truncate text-sm text-gray-800 dark:text-neutral-200">{s.title}</p>
            <p className="text-[11px] text-gray-400 dark:text-neutral-500">{formatAge(s.updatedAt)} ago</p>
          </button>
          <button
            onClick={() => onDelete(s.id)}
            aria-label={`Delete chat "${s.title}"`}
            className="shrink-0 mr-1.5 p-1.5 rounded-md text-gray-400 dark:text-neutral-500
                       opacity-0 group-hover:opacity-100 focus:opacity-100
                       hover:text-red-600 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-500/10
                       transition-all"
          >
            <IconTrash className="w-3.5 h-3.5" />
          </button>
        </div>
      ))}
    </div>
  )
}
