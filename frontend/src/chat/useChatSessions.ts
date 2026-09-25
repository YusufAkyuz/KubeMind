import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export interface ChatSessionSummary {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface StoredChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

export function chatSessionsKey(clusterId: string | null) {
  return ['chat-sessions', clusterId] as const
}

/**
 * The signed-in user's saved conversations for one cluster. The server scopes
 * this to the caller, so there is nothing user-specific to pass — and nothing
 * a caller could pass to widen it.
 *
 * Only fetched while the panel is open: a closed chat widget shouldn't be
 * issuing requests on every page the user visits.
 */
export function useChatSessions(clusterId: string | null, enabled: boolean) {
  return useQuery<ChatSessionSummary[]>({
    queryKey: chatSessionsKey(clusterId),
    queryFn: async () => (await api.get<ChatSessionSummary[]>(`/clusters/${clusterId}/chat/sessions`)).data,
    enabled: !!clusterId && enabled,
    staleTime: 10_000,
  })
}

export function useRefreshChatSessions(clusterId: string | null) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: chatSessionsKey(clusterId) })
}

export async function fetchTranscript(clusterId: string, sessionId: string) {
  return (await api.get<StoredChatMessage[]>(`/clusters/${clusterId}/chat/sessions/${sessionId}`)).data
}

export async function deleteChatSession(clusterId: string, sessionId: string) {
  await api.delete(`/clusters/${clusterId}/chat/sessions/${sessionId}`)
}

/** Titles are derived from the first question, which is often a poor name for the chat. */
export async function renameChatSession(clusterId: string, sessionId: string, title: string) {
  await api.patch(`/clusters/${clusterId}/chat/sessions/${sessionId}`, { title })
}
