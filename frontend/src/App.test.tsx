import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import App from './App'
import { renderWithProviders } from './test/renderWithProviders'
import { mockGet } from './test/mockApi'

const { mockApi, authState } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  // Mutable so a single test can move it between two logged-in identities
  // without going through a real login/logout network round trip — the thing
  // under test is what ChatWidget does when useAuth().username changes, not
  // how that change gets triggered.
  authState: { username: 'bob', role: 'USER', isAdmin: false },
}))
vi.mock('./api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))
vi.mock('./auth/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./auth/AuthContext')>()
  return {
    ...actual,
    useAuth: () => ({ ...authState, loading: false, login: vi.fn(), logout: vi.fn() }),
  }
})

const CLUSTER = { id: 5, name: 'eba-ex', status: 'APPROVED', builtIn: false }

beforeEach(() => {
  mockApi.get.mockReset()
  authState.username = 'bob'
  authState.role = 'USER'
  authState.isAdmin = false
})

/**
 * Regression test for a real bug found in manual testing: ChatWidget mounts
 * once outside the routed page tree (see App.tsx) and its own guard
 * (`if (!username) return null`) only hides it, it never unmounts — so its
 * `messages` state survived a logout and the next person to log in in the
 * same tab saw the previous account's conversation. Fixed by keying
 * ChatWidget on username so React remounts it, rather than by reaching into
 * ChatWidget to manually clear state on an effect.
 */
describe('App — chat history does not leak between accounts in the same tab', () => {
  it('clears the chat when a different account logs in without a page reload', async () => {
    mockGet(mockApi, { '/clusters': [CLUSTER] })
    // An unmatched route so no page component mounts under <Routes> — only the
    // app-shell chrome (ChatWidget, the terminal host) does, keeping this test
    // scoped to the bug rather than any one page's own API contract.
    const { rerender } = renderWithProviders(<App />, { route: '/route-matching-nothing' })

    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText('Open AI assistant'))
    await waitFor(() => expect(screen.getByPlaceholderText('Ask anything…')).not.toBeDisabled())

    await userEvent.type(screen.getByPlaceholderText('Ask anything…'), "bob's private question")
    expect(screen.getByDisplayValue("bob's private question")).toBeInTheDocument()

    // "admin logs in" in the same tab, no navigation/reload.
    authState.username = 'admin'
    authState.role = 'ADMIN'
    authState.isAdmin = true
    rerender(<App />)

    // chatPanel.isOpen lives in ChatPanelContext, outside ChatWidget's own
    // remount boundary, so the panel staying open here is expected — it's
    // ChatWidget's OWN state (the draft input, sent messages) that must not
    // survive the account switch, and does via key={username} in App.tsx.
    await waitFor(() => expect(screen.getByPlaceholderText('Ask anything…')).toHaveValue(''))
    expect(screen.queryByDisplayValue("bob's private question")).not.toBeInTheDocument()
    expect(screen.queryByText("bob's private question")).not.toBeInTheDocument()
  })
})
