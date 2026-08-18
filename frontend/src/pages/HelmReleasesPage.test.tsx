import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { HelmReleasesPage } from './HelmReleasesPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

const PATH = '/clusters/:clusterId/namespaces/:ns/helm/releases'
const ROUTE = '/clusters/7/namespaces/monitoring/helm/releases'
const RELEASE = {
  name: 'grafana', namespace: 'monitoring', revision: '3', updated: '2026-08-02T11:00:00Z',
  status: 'deployed', chart: 'grafana-10.5.15', appVersion: '11.0.0',
}
/** A release nobody installed through KubeMind: no chart reference resolved. */
const DETAIL_WITHOUT_CHART = { values: 'replicas: 1\n', manifest: '', notes: '', chartRef: null }
const HISTORY = [
  { revision: 1, updated: '2026-08-01T10:00:00Z', status: 'superseded', chart: 'grafana-10.5.15', appVersion: '11.0.0', description: 'Install complete' },
  { revision: 2, updated: '2026-08-01T18:00:00Z', status: 'superseded', chart: 'grafana-10.5.15', appVersion: '11.0.0', description: 'Upgrade complete' },
  { revision: 3, updated: '2026-08-02T11:00:00Z', status: 'deployed', chart: 'grafana-10.5.15', appVersion: '11.0.0', description: 'Upgrade complete' },
]

const BASE = '/clusters/7/namespaces/monitoring/helm/releases'

beforeEach(() => {
  mockApi.get.mockReset()
  mockApi.post.mockReset().mockResolvedValue({ data: null })
  mockGet(mockApi, {
    '/auth/me': { username: 'admin', role: 'ADMIN' },
    [BASE]: [RELEASE],
    [`${BASE}/grafana`]: DETAIL_WITHOUT_CHART,
    [`${BASE}/grafana/history`]: HISTORY,
    '/clusters/7/helm/repos': [],
    // Layout's sidebar fetches these on every page.
    '/clusters': [{ id: 7, name: 'staging', status: 'APPROVED', builtIn: false }],
    '/clusters/7/namespaces': [{ name: 'monitoring' }],
  })
})

/** The page reads clusterId/ns from the route, so it has to mount under a real
 *  Route — MemoryRouter alone leaves useParams empty. */
function renderPage() {
  return renderWithProviders(
    <Routes><Route path={PATH} element={<HelmReleasesPage />} /></Routes>,
    { route: ROUTE },
  )
}

async function openHistoryTab() {
  renderPage()
  await userEvent.click(await screen.findByText('grafana'))
  await userEvent.click(await screen.findByRole('button', { name: 'history' }))
}

/**
 * Rollback is the one release repair that needs no chart reference — Helm
 * replays the chart it stored with the revision. These lock in that the UI
 * offers it on a release installed from a terminal, where "Save & Upgrade" is
 * still disabled for want of a chartRef.
 */
describe('HelmReleasesPage — revision history and rollback', () => {
  it('lists revisions newest first without needing a chart reference', async () => {
    await openHistoryTab()

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(`${BASE}/grafana/history`))
    const rows = await screen.findAllByText(/complete$/)
    expect(rows).toHaveLength(3)
    // Newest at the top: #3 precedes #1 in the DOM.
    const order = screen.getAllByText(/^#\d$/).map((el) => el.textContent)
    expect(order).toEqual(['#3', '#2', '#1'])
  })

  it('is not fetched until the tab is actually opened', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(`${BASE}/grafana`))

    expect(mockApi.get).not.toHaveBeenCalledWith(`${BASE}/grafana/history`)
  })

  it('offers rollback on superseded revisions and not on the deployed one', async () => {
    await openHistoryTab()
    await screen.findByText('Install complete')

    const current = screen.getByRole('button', { name: 'Current' })
    expect(current).toBeDisabled()
    expect(screen.getAllByRole('button', { name: 'Rollback' })).toHaveLength(2)
  })

  it('rolls back to the chosen revision after confirming', async () => {
    await openHistoryTab()
    const firstRevision = (await screen.findByText('Install complete')).closest('div')!.parentElement!

    await userEvent.click(within(firstRevision).getByRole('button', { name: 'Rollback' }))
    // The dialog names the revision so nobody confirms a rollback to the wrong one.
    expect(await screen.findByText('Roll back to revision 1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Roll back' }))

    await waitFor(() => expect(mockApi.post).toHaveBeenCalledWith(
      `${BASE}/grafana/rollback`, { revision: 1 }))
  })

  it('does nothing until the confirmation is accepted', async () => {
    await openHistoryTab()
    await screen.findByText('Install complete')

    await userEvent.click(screen.getAllByRole('button', { name: 'Rollback' })[0])
    await screen.findByText(/Roll back to revision/)
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(mockApi.post).not.toHaveBeenCalled()
  })
})
