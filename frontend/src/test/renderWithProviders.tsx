import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '../auth/AuthContext'
import { ToastProvider } from '../components/Toast'
import { TerminalPanelProvider } from '../terminal/TerminalPanelContext'
import { ChatPanelProvider } from '../chat/ChatPanelContext'
import { RightReserveProvider } from '../layout/RightReserveContext'

/** Retries off — a mocked-rejection test would otherwise sit through
 *  TanStack Query's default backoff before the assertion ever runs. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  })
}

interface Options {
  /** Initial URL — most of this app's behavior branches on the route
   *  (which cluster is "current", whether a page is namespace-scoped). */
  route?: string
  queryClient?: QueryClient
}

/**
 * The provider stack every real page mounts under (see App.tsx), minus the
 * router (routes aren't under test, this is component/hook-level) and minus
 * ProtectedRoute (auth state is supplied directly via the mocked
 * GET /auth/me response, not by simulating a redirect).
 *
 * AuthProvider fires a real `api.get('/auth/me')` on mount — the caller's
 * `api` mock must have a fixture for it (see test/mockApi.ts), same as any
 * other endpoint the component under test happens to call.
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/', queryClient = createTestQueryClient() }: Options = {},
) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <AuthProvider>
            {/* ToastProvider sits directly inside AuthProvider in main.tsx —
                pages that report success/failure through useToast throw
                without it. */}
            <ToastProvider>
              <RightReserveProvider>
                <ChatPanelProvider>
                  <TerminalPanelProvider>{children}</TerminalPanelProvider>
                </ChatPanelProvider>
              </RightReserveProvider>
            </ToastProvider>
          </AuthProvider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }
  return render(ui, { wrapper: Wrapper })
}
