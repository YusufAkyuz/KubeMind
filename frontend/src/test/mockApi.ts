import { vi } from 'vitest'

/**
 * Every component in this app reads/writes through the single `api` axios
 * instance (see api/client.ts) — so mocking that one module is enough to
 * control every network call a component makes, without touching MSW or a
 * real server. A test file does:
 *
 *   // vi.mock factories are hoisted above imports, so a module-scoped const
 *   // they close over has to go through vi.hoisted (plain top-level consts
 *   // hit a TDZ error at hoist time).
 *   const { mockApi } = vi.hoisted(() => ({
 *     mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
 *   }))
 *   vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))
 *
 *   import { api } from '../api/client'
 *   import { mockGet } from '../test/mockApi'
 *   ...
 *   mockGet(api, { '/auth/me': { username: 'bob', role: 'USER' }, '/clusters': [] })
 *
 * `mockGet` matches by exact path suffix (the part after `/api`, e.g. what a
 * component passes to `api.get(...)`) so a fixture doesn't have to special-case
 * query strings — GET /clusters/7/namespaces?namespace=default still matches
 * a `/clusters/7/namespaces` entry.
 */
type Responder = unknown | (() => unknown)

/** Registers GET responses on an already-mocked `api` object, keyed by the
 *  path passed to `api.get`. Unregistered paths reject, so a component that
 *  fires a request no test expected fails loudly instead of hanging. An
 *  `Error` instance as the fixture value makes that path reject instead of
 *  resolve — for simulating a 401 on GET /auth/me before login, for example. */
export function mockGet(api: { get: ReturnType<typeof vi.fn> }, routes: Record<string, Responder>) {
  api.get.mockImplementation((path: string) => {
    const match = Object.keys(routes).find((p) => path === p || path.startsWith(`${p}?`))
    if (!match) {
      return Promise.reject(new Error(`mockGet: no fixture registered for GET ${path}`))
    }
    const value = routes[match]
    if (value instanceof Error) {
      return Promise.reject(value)
    }
    const data = typeof value === 'function' ? (value as () => unknown)() : value
    return Promise.resolve({ data })
  })
}
