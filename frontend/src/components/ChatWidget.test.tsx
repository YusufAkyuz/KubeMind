import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChatWidget } from './ChatWidget'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

const OWNED_CLUSTER = { id: 5, name: 'eba-ex', status: 'APPROVED', builtIn: false }

beforeEach(() => {
  mockApi.get.mockReset()
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
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' }, '/clusters': [OWNED_CLUSTER] })
    renderWithProviders(<ChatWidget />, { route: '/settings/clusters' })

    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText('Open AI assistant'))

    await waitFor(() => expect(screen.getByPlaceholderText('Ask anything…')).not.toBeDisabled())
  })

  it('disables the composer instead of guessing when the caller owns no cluster at all', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' }, '/clusters': [] })
    renderWithProviders(<ChatWidget />, { route: '/settings/clusters' })

    await waitFor(() => expect(screen.getByLabelText('Open AI assistant')).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText('Open AI assistant'))

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters'))
    expect(screen.getByPlaceholderText('Register a cluster first to chat')).toBeDisabled()
  })
})
