import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TerminalPanelProvider, useTerminalPanel } from './TerminalPanelContext'

const { authState } = vi.hoisted(() => ({
  // Mutable so the test can move it between two identities without a real
  // login/logout round trip — see App.test.tsx for the same pattern applied
  // to the chat-history-leak bug this mirrors.
  authState: { username: 'bob' as string | null },
}))
vi.mock('../auth/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../auth/AuthContext')>()
  return { ...actual, useAuth: () => ({ ...authState, isAdmin: false, loading: false, login: vi.fn(), logout: vi.fn() }) }
})

function Harness() {
  const panel = useTerminalPanel()
  return (
    <div>
      <span data-testid="count">{panel.sessions.length}</span>
      <button onClick={() => panel.openPodExec('7', 'default', 'api-7d9f', ['app'])}>open</button>
    </div>
  )
}

beforeEach(() => {
  authState.username = 'bob'
})

/**
 * Regression test for the terminal-side twin of the chat-history-leak bug: a
 * privileged WebSocket session (pod exec, node shell, or — worst case —
 * Cluster Terminal, which holds cluster-admin for the session) opened by one
 * account used to stay open, tab and command transcript included, after that
 * account logged out or a different one logged in in the same tab. The panel
 * lives above <Routes> deliberately so tabs survive page navigation; it must
 * NOT survive the identity that opened them changing.
 */
describe('TerminalPanelContext — sessions do not survive an identity change', () => {
  it('closes every open session on logout (username becomes null)', async () => {
    function Wrapper({ user }: { user: string | null }) {
      authState.username = user
      return <TerminalPanelProvider><Harness /></TerminalPanelProvider>
    }
    const { rerender } = render(<Wrapper user="bob" />)

    await userEvent.click(screen.getByText('open'))
    expect(screen.getByTestId('count')).toHaveTextContent('1')

    rerender(<Wrapper user={null} />)

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('0'))
  })

  it('closes every open session when a different account logs in without a reload', async () => {
    function Wrapper({ user }: { user: string | null }) {
      authState.username = user
      return <TerminalPanelProvider><Harness /></TerminalPanelProvider>
    }
    const { rerender } = render(<Wrapper user="bob" />)

    await userEvent.click(screen.getByText('open'))
    expect(screen.getByTestId('count')).toHaveTextContent('1')

    rerender(<Wrapper user="admin" />)

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('0'))
  })
})
