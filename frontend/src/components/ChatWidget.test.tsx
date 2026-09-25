import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChatWidget } from './ChatWidget'
import { StreamHttpError } from '../utils/streamFetch'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi, mockStream } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  mockStream: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))
// The chat send path goes through fetch(), not the axios instance, so it needs
// its own stand-in — one that also plays back the session-id response header.
// StreamHttpError stays real — the recovery path branches on `instanceof`.
vi.mock('../utils/streamFetch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/streamFetch')>()
  return { ...actual, streamText: mockStream }
})

const OWNED_CLUSTER = { id: 5, name: 'eba-ex', status: 'APPROVED', builtIn: false }
const SESSIONS = [
  { id: 'sess-1', title: 'Why is payments crashing?', createdAt: NOW(), updatedAt: NOW() },
  { id: 'sess-2', title: 'How do I roll back?', createdAt: NOW(), updatedAt: NOW() },
]

function NOW() {
  return new Date().toISOString()
}

beforeEach(() => {
  mockApi.get.mockReset()
  mockApi.delete.mockReset().mockResolvedValue({ data: null })
  mockApi.patch.mockReset().mockResolvedValue({ data: null })
  // The real server reserves a fresh answer id per request, so the stand-in
  // does too — a fixed id would hide a duplicate-key bug behind a passing test.
  let turn = 0
  mockStream.mockReset().mockImplementation(
    async (
      _url: string,
      _body: unknown,
      onChunk: (c: string) => void,
      options?: { onResponse?: (res: Response) => void },
    ) => {
      turn += 1
      options?.onResponse?.({
        headers: {
          // Distinct from the stored-transcript fixture ids below, so a real
          // duplicate-key regression is not masked by a naming collision here.
          get: (h: string) => (h === 'X-Chat-Session-Id' ? 'sess-1' : `live-msg-${turn}`),
        },
      } as unknown as Response)
      onChunk('an answer')
    },
  )
})

// Regression tests for the bug fixed alongside Sidebar's (commit 2a6f527):
// clusterId used to fall back to '0' — the ADMIN-only built-in cluster — off a
// /clusters/:id page, so a USER's chat send silently 403'd from any other
// page (Settings, Users, Audit). It now falls back to the first cluster the
// caller actually owns, or to null (composer disabled) when there is none.
describe('ChatWidget — clusterId off a non-cluster page', () => {
  it('never falls back to the built-in cluster (id 0) when idle on a non-cluster page', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' }, '/clusters': [OWNED_CLUSTER] })
    renderWithProviders(<ChatWidget />, { route: '/settings/clusters' })

    // The launcher only renders once useAuth resolves a username.
    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))

    expect(mockApi.get).not.toHaveBeenCalledWith(expect.stringContaining('/clusters/0/'))
  })

  it('enables the composer using the first owned cluster once loaded', async () => {
    mockGet(mockApi, {
      '/auth/me': { username: 'bob', role: 'USER' },
      '/clusters': [OWNED_CLUSTER],
      '/clusters/5/chat/sessions': [],
    })
    renderWithProviders(<ChatWidget />, { route: '/settings/clusters' })

    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText('Open AI assistant'))

    await waitFor(() => expect(screen.getByPlaceholderText('Ask anything…')).not.toBeDisabled())
  })

  it('disables the composer instead of guessing when the caller owns no cluster at all', async () => {
    // No cluster means no session list to fetch either.
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' }, '/clusters': [] })
    renderWithProviders(<ChatWidget />, { route: '/settings/clusters' })

    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText('Open AI assistant'))

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))
    expect(screen.getByPlaceholderText('Register a cluster first to chat')).toBeDisabled()
  })
})

/** Opens the panel on a cluster page with the given session list registered. */
async function openPanel(sessions: unknown = SESSIONS) {
  mockGet(mockApi, {
    '/auth/me': { username: 'bob', role: 'USER' },
    '/clusters': [OWNED_CLUSTER],
    '/clusters/5/chat/sessions': sessions,
    '/clusters/5/chat/sessions/sess-1': [
      { id: 'msg-1', role: 'user', content: 'Why is payments crashing?', createdAt: NOW() },
      { id: 'msg-2', role: 'assistant', content: 'It is OOMKilled.', createdAt: NOW() },
    ],
  })
  renderWithProviders(<ChatWidget />, { route: '/clusters/5/nodes' })
  await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
  await userEvent.click(screen.getByLabelText('Open AI assistant'))
}

describe('ChatWidget — saved conversations', () => {
  it('lists the saved chats and reopens one from history', async () => {
    await openPanel()

    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())

    await userEvent.click(screen.getByText('Why is payments crashing?'))

    // The whole transcript comes back, not just the title.
    await waitFor(() => expect(screen.getByText('It is OOMKilled.')).toBeInTheDocument())
    expect(mockApi.get).toHaveBeenCalledWith('/clusters/5/chat/sessions/sess-1')
  })

  it('keeps a reopened conversation going instead of starting a second one', async () => {
    await openPanel()
    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())
    await userEvent.click(screen.getByText('Why is payments crashing?'))
    await waitFor(() => expect(screen.getByText('It is OOMKilled.')).toBeInTheDocument())

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'and now?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    // This is the bug the header/sessionId round trip exists to prevent: sending
    // sessionId: null here would silently fork a new conversation every turn.
    await waitFor(() => expect(mockStream).toHaveBeenCalled())
    expect(mockStream.mock.calls[0][1]).toEqual({ sessionId: 'sess-1', message: 'and now?' })
  })

  it('adopts the id the server assigns so the second message continues the first', async () => {
    await openPanel([])

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'first question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(1))
    expect(mockStream.mock.calls[0][1]).toEqual({ sessionId: null, message: 'first question' })

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'second question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2))
    // 'sess-1' is what the mocked X-Chat-Session-Id header handed back.
    expect(mockStream.mock.calls[1][1]).toEqual({ sessionId: 'sess-1', message: 'second question' })
  })

  it('drops the active session when starting a new chat', async () => {
    await openPanel([])

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'first question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(screen.getByText('an answer')).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText('New chat'))

    expect(screen.queryByText('an answer')).not.toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'unrelated question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2))
    expect(mockStream.mock.calls[1][1]).toEqual({ sessionId: null, message: 'unrelated question' })
  })

  /**
   * Chat ratings used to be filed against a random client-side id, so a
   * thumbs-up could never be joined back to the answer it rated — the feedback
   * table filled with rows pointing at nothing. The answer's real row id now
   * arrives in a response header before the first token.
   */
  it('files feedback against the stored answer, not a throwaway client id', async () => {
    await openPanel([])
    mockApi.post.mockResolvedValue({ data: null })

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'why is it broken?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(screen.getByLabelText('Mark as helpful')).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText('Mark as helpful'))

    await waitFor(() => expect(mockApi.post).toHaveBeenCalledWith(
      '/clusters/5/ai-feedback',
      { surface: 'CHAT', contextHash: 'live-msg-1', rating: 'UP' },
    ))
  })

  it('renames a chat from the history list', async () => {
    await openPanel()
    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText('Rename chat "Why is payments crashing?"'))
    const input = screen.getByLabelText('Chat title')
    await userEvent.clear(input)
    await userEvent.type(input, 'Payments OOM investigation{Enter}')

    await waitFor(() => expect(mockApi.patch).toHaveBeenCalledWith(
      '/clusters/5/chat/sessions/sess-1',
      { title: 'Payments OOM investigation' },
    ))
  })

  it('keeps the old title when a rename is emptied out', async () => {
    await openPanel()
    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText('Rename chat "Why is payments crashing?"'))
    await userEvent.clear(screen.getByLabelText('Chat title'))
    await userEvent.keyboard('{Enter}')

    // A blank title would leave a row nobody can identify in the list.
    expect(mockApi.patch).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())
  })

  /**
   * The wait before a local model's first token runs to several seconds, during
   * which the bubble used to hold a static "…" — indistinguishable from a
   * request that had stalled. The indicator has to survive the whole turn, not
   * just the empty part, and has to be gone once the answer is final.
   */
  it('shows a generating indicator for the whole turn and drops it when the answer lands', async () => {
    await openPanel([])
    let finishStream: () => void = () => {}
    mockStream.mockImplementation(
      async (
        _url: string,
        _body: unknown,
        onChunk: (c: string) => void,
        options?: { onResponse?: (res: Response) => void },
      ) => {
        options?.onResponse?.({
          headers: { get: (h: string) => (h === 'X-Chat-Session-Id' ? 'sess-1' : 'live-msg-1') },
        } as unknown as Response)
        await new Promise<void>((resolve) => {
          finishStream = () => { onChunk('partial answer'); resolve() }
        })
      },
    )

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'slow question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    // Nothing has streamed yet: the indicator is the only thing in the bubble.
    await waitFor(() => expect(screen.getByRole('status', { name: 'Generating response' })).toBeInTheDocument())

    // act() because the chunk lands from outside React's event loop here, the
    // way a real network chunk does.
    await act(async () => { finishStream() })

    // Text arrived and the turn is done — the indicator must not linger.
    await waitFor(() => expect(screen.getByText('partial answer')).toBeInTheDocument())
    await waitFor(() =>
      expect(screen.queryByRole('status', { name: 'Generating response' })).not.toBeInTheDocument())
  })

  /**
   * Found live: deleting a conversation elsewhere (another tab, another device)
   * left this panel holding the dead id, and every further message 404'd
   * forever — "New chat" was the only escape, and the question the user had
   * just typed was lost to an error bubble. A stale id is our bookkeeping
   * problem, so the turn is replayed once as a new conversation.
   */
  it('recovers when the conversation was deleted somewhere else mid-chat', async () => {
    await openPanel()
    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())
    await userEvent.click(screen.getByText('Why is payments crashing?'))
    await waitFor(() => expect(screen.getByText('It is OOMKilled.')).toBeInTheDocument())

    // The server no longer knows this session; the retry against a fresh one works.
    mockStream
      .mockRejectedValueOnce(new StreamHttpError(404, 'Request failed (404)'))
      .mockImplementationOnce(async (
        _url: string,
        _body: unknown,
        onChunk: (c: string) => void,
        options?: { onResponse?: (res: Response) => void },
      ) => {
        options?.onResponse?.({
          headers: { get: (h: string) => (h === 'X-Chat-Session-Id' ? 'sess-new' : 'live-msg-1') },
        } as unknown as Response)
        onChunk('answered anyway')
      })

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'and now?')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(mockStream).toHaveBeenCalledTimes(2))
    expect(mockStream.mock.calls[0][1]).toEqual({ sessionId: 'sess-1', message: 'and now?' })
    // Replayed without the dead id rather than surfaced as a dead end.
    expect(mockStream.mock.calls[1][1]).toEqual({ sessionId: null, message: 'and now?' })
    await waitFor(() => expect(screen.getByText('answered anyway')).toBeInTheDocument())
    expect(screen.queryByText(/Request failed/)).not.toBeInTheDocument()
  })

  it('still surfaces failures that are not a vanished conversation', async () => {
    await openPanel([])
    mockStream.mockRejectedValue(new StreamHttpError(502, 'Request failed (502)'))

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), 'question')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    // One attempt only — retrying a 502 against a new session would not help.
    await waitFor(() => expect(screen.getByText('[Request failed (502)]')).toBeInTheDocument())
    expect(mockStream).toHaveBeenCalledTimes(1)
  })

  it('clears the panel when the chat being deleted is the one on screen', async () => {
    await openPanel()
    await userEvent.click(screen.getByLabelText('Chat history'))
    await waitFor(() => expect(screen.getByText('Why is payments crashing?')).toBeInTheDocument())
    await userEvent.click(screen.getByText('Why is payments crashing?'))
    await waitFor(() => expect(screen.getByText('It is OOMKilled.')).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText('Chat history'))
    await userEvent.click(screen.getByLabelText('Delete chat "Why is payments crashing?"'))

    await waitFor(() =>
      expect(mockApi.delete).toHaveBeenCalledWith('/clusters/5/chat/sessions/sess-1'))
    expect(screen.queryByText('It is OOMKilled.')).not.toBeInTheDocument()
  })
})
