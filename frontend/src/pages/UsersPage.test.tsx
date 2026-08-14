import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UsersPage } from './UsersPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

const ADMIN = { username: 'admin', role: 'ADMIN' }
const USERS = [
  { id: 1, username: 'admin', role: 'ADMIN', identityProvider: 'local' },
  { id: 2, username: 'alice', role: 'USER', identityProvider: 'oidc' },
]

beforeEach(() => {
  mockApi.get.mockReset()
  mockApi.put.mockReset()
  mockGet(mockApi, { '/auth/me': ADMIN, '/users': USERS })
})

/** Scoped to the table on purpose: Layout's sidebar also renders the logged-in
 *  username, so a bare findByText would be ambiguous for "admin". */
async function userRow(username: string) {
  const table = await screen.findByRole('table')
  return within(table).getByText(username).closest('tr')!
}

async function openManageFor(username: string) {
  await userEvent.click(within(await userRow(username)).getByRole('button', { name: 'Manage' }))
}

/**
 * An OIDC account's whole point is that revoking it in the identity provider
 * revokes it here. A local password would survive that, so the backend refuses
 * to set one (UserService.resetPassword) — these lock in that the UI doesn't
 * offer the control either, and says why.
 */
describe('UsersPage — SSO accounts have no local password', () => {
  it('marks an SSO-provisioned account in the list', async () => {
    renderWithProviders(<UsersPage />)

    expect(within(await userRow('alice')).getByText('SSO')).toBeInTheDocument()
    expect(within(await userRow('admin')).queryByText('SSO')).not.toBeInTheDocument()
  })

  it('offers no password reset for an SSO account, and explains why', async () => {
    renderWithProviders(<UsersPage />)
    await openManageFor('alice')

    expect(screen.queryByPlaceholderText('Min 8 characters')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /reset password/i })).not.toBeInTheDocument()
    expect(screen.getByText(/signs in through your identity provider/i)).toBeInTheDocument()
  })

  it('still offers password reset for a local account', async () => {
    mockApi.put.mockResolvedValue({ data: {} })
    renderWithProviders(<UsersPage />)
    await openManageFor('admin')

    const field = screen.getByPlaceholderText('Min 8 characters')
    await userEvent.type(field, 'newpassword1')
    await userEvent.click(screen.getByRole('button', { name: /reset password/i }))

    await waitFor(() =>
      expect(mockApi.put).toHaveBeenCalledWith('/users/1/password', { password: 'newpassword1' }))
  })
})
