import { useState } from 'react'
import { api } from '../api/client'
import { IconThumbDown, IconThumbUp } from './Icons'

type Surface = 'EXPLAIN' | 'CHAT' | 'DRAFT' | 'ANALYZE' | 'EDIT'
type Rating = 'UP' | 'DOWN'

interface Props {
  clusterId: string
  surface: Surface
  /** A key tying this rating to the answer it's about — the diagnosis's cache
   *  state_hash for Explain, a client-generated id for stateless surfaces like Chat. */
  contextHash: string
}

/** Thumbs up/down on an AI answer. Fire-and-forget: rating never blocks or errors
 *  visibly, since it's a side note on an answer the user is already looking at,
 *  not something that should ever break the surrounding page (see
 *  KubeMind-AI-Plan.md §4/A4 — feeds a future eval harness / feedback dataset). */
export function AiFeedbackButtons({ clusterId, surface, contextHash }: Props) {
  const [voted, setVoted] = useState<Rating | null>(null)

  const vote = async (rating: Rating) => {
    if (voted) return
    setVoted(rating) // optimistic — a failed feedback ping isn't worth surfacing an error for
    try {
      await api.post(`/clusters/${clusterId}/ai-feedback`, { surface, contextHash, rating })
    } catch {
      setVoted(null)
    }
  }

  return (
    <div className="flex items-center gap-1">
      <button
        onClick={() => vote('UP')}
        disabled={voted !== null}
        title="Helpful"
        aria-label="Mark as helpful"
        className={`p-1 rounded transition-colors ${
          voted === 'UP' ? 'text-emerald-600 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-500/10'
          : voted === 'DOWN' ? 'text-gray-300 dark:text-slate-600 cursor-default'
          : 'text-gray-400 dark:text-slate-500 hover:text-emerald-600 hover:bg-emerald-50'
        }`}
      >
        <IconThumbUp className="w-3.5 h-3.5" />
      </button>
      <button
        onClick={() => vote('DOWN')}
        disabled={voted !== null}
        title="Not helpful"
        aria-label="Mark as not helpful"
        className={`p-1 rounded transition-colors ${
          voted === 'DOWN' ? 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-500/10'
          : voted === 'UP' ? 'text-gray-300 dark:text-slate-600 cursor-default'
          : 'text-gray-400 dark:text-slate-500 hover:text-red-600 hover:bg-red-50'
        }`}
      >
        <IconThumbDown className="w-3.5 h-3.5" />
      </button>
    </div>
  )
}
