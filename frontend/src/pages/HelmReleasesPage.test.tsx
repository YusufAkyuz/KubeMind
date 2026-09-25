import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { AxiosError, AxiosHeaders } from 'axios'
import { HelmReleasesPage } from './HelmReleasesPage'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
// The real apiErrorMessage, because what regressed across 27 pages was the
// wiring: they rendered axios's own `.message` and dropped the server's.
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { api: mockApi, apiErrorMessage: actual.apiErrorMessage }
})

const PATH = '/clusters/:clusterId/namespaces/:ns/helm/releases'
const ROUTE = '/clusters/7/namespaces/monitoring/helm/releases'
const RELEASE = {
  name: 'grafana', namespace: 'monitoring', revision: '3', updated: '2026-08-02T11:00:00Z',
  status: 'deployed', chart: 'grafana-10.5.15', appVersion: '11.0.0',
}
/** What the detail endpoint always returns: credentials masked. A release nobody
 *  installed through KubeMind, so no repo reference — the chart Helm stored in
 *  the cluster is what makes it editable, once revealed. */
const DETAIL_FROM_STORED_CHART = {
  values: 'replicas: 1\napiToken: [REDACTED]\n', manifest: '', notes: '',
  chartRef: null, valuesEditable: true, masked: true,
}
/** Same release after an audited reveal. */
const DETAIL_REVEALED = {
  values: 'replicas: 1\napiToken: s3cret\n', manifest: '', notes: '',
  chartRef: null, valuesEditable: true, masked: false,
}
/** Nothing registered that matches this release — the common case for a
 *  terminal install, and no longer a problem. */
const NO_REPO_LINKED = {
  chartRef: null, currentVersion: '10.5.15', latestVersion: null, updateAvailable: false,
}
const UPDATE_AVAILABLE = {
  chartRef: 'bitnami/grafana', currentVersion: '10.5.15', latestVersion: '11.2.0', updateAvailable: true,
}
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
    [`${BASE}/grafana`]: DETAIL_FROM_STORED_CHART,
    [`${BASE}/grafana/reveal`]: DETAIL_REVEALED,
    [`${BASE}/grafana/history`]: HISTORY,
    [`${BASE}/grafana/chart-update`]: NO_REPO_LINKED,
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
 * Every list page used to render `(error as Error).message`, which for an axios
 * failure is "Request failed with status code 403" — the server's explanation of
 * *why* was thrown away at the last step. This is that path end to end.
 */
describe('HelmReleasesPage — a refusal explains itself', () => {
  it('shows what the cluster actually refused, not the HTTP status text', async () => {
    const explained = 'ServiceAccount "kubemind-developer" (namespace dev-team) is not allowed to '
      + 'list secrets in namespace dev-team. Helm keeps its releases in Secrets, so managing them here '
      + 'needs get and list on secrets.'
    const refusal = new AxiosError('Request failed with status code 403')
    refusal.response = {
      status: 403, statusText: 'Forbidden', data: { error: explained },
      headers: new AxiosHeaders(), config: { headers: new AxiosHeaders() },
    }
    mockGet(mockApi, {
      '/auth/me': { username: 'admin', role: 'ADMIN' },
      [BASE]: refusal,
      '/clusters': [{ id: 7, name: 'staging', status: 'APPROVED', builtIn: false }],
      '/clusters/7/namespaces': [{ name: 'monitoring' }],
    })
    renderPage()

    expect(await screen.findByText(new RegExp('not allowed to list secrets'))).toBeInTheDocument()
    expect(screen.queryByText(/status code 403/)).not.toBeInTheDocument()
  })
})

/**
 * Both of these work off what Helm stored in the cluster rather than a chart
 * repository, which is what makes a release installed from a terminal fully
 * manageable here — the thing that used to force people to add a repo first.
 */
describe('HelmReleasesPage — managing a release with no repository registered', () => {
  it('keeps values editable when only the stored chart is available', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await userEvent.click(await screen.findByRole('button', { name: 'Reveal' }))

    const editor = await screen.findByDisplayValue(/s3cret/)
    expect(editor).not.toHaveAttribute('readonly')
    // Linking is offered as one muted line, not as pickers sitting above the
    // editor — three form controls there read as a required step whatever the
    // copy says, which is how this shipped wrong the first time.
    expect(screen.getByRole('button', { name: 'Link a chart' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Link' })).not.toBeInTheDocument()
    expect(screen.queryByText(/enable editing/)).not.toBeInTheDocument()
  })

  it('only shows the chart pickers once they are asked for', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await screen.findByRole('textbox')

    await userEvent.click(screen.getByRole('button', { name: 'Link a chart' }))

    expect(screen.getByRole('button', { name: 'Link' })).toBeInTheDocument()
    expect(screen.getByText(/used only for version-update notices/)).toBeInTheDocument()
  })

  it('saves values without sending a chart reference', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await userEvent.click(await screen.findByRole('button', { name: 'Reveal' }))
    const editor = await screen.findByDisplayValue(/s3cret/)

    await userEvent.clear(editor)
    await userEvent.type(editor, 'replicas: 5')
    await userEvent.click(screen.getByRole('button', { name: 'Save & Upgrade' }))

    // The backend resolves the chart itself; the client no longer needs to know one.
    await waitFor(() => expect(mockApi.post).toHaveBeenCalledWith(
      `${BASE}/grafana/values`, { valuesYaml: 'replicas: 5' }))
  })
})

/**
 * Chart values carry passwords and signing keys, and the manifest carries
 * rendered Secrets. The Secrets page has always required an ADMIN and written an
 * audit record to show that data; the Helm page used to hand it to anyone who
 * could open the drawer.
 */
describe('HelmReleasesPage — credentials are hidden until revealed', () => {
  it('shows masked values and refuses edits until they are revealed', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))

    const editor = await screen.findByRole('textbox')
    expect(editor).toHaveValue('replicas: 1\napiToken: [REDACTED]\n')
    // Editing masked text would write the mask over the real credential.
    expect(editor).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save & Upgrade' })).not.toBeInTheDocument()
    expect(screen.getByText(/Credentials are hidden/)).toBeInTheDocument()
  })

  it('fetches the audited reveal endpoint rather than unmasking client-side', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await userEvent.click(await screen.findByRole('button', { name: 'Reveal' }))

    // The masked payload never contained the secret, so it has to come from the
    // server — where the ADMIN check and the audit record live.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith(`${BASE}/grafana/reveal`))
    expect(await screen.findByDisplayValue(/s3cret/)).toBeInTheDocument()
  })

  it('re-masks when a different release is opened', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await userEvent.click(await screen.findByRole('button', { name: 'Reveal' }))
    await screen.findByDisplayValue(/s3cret/)

    // Closing and reopening must not carry the reveal over.
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    await userEvent.click(await screen.findByText('grafana'))

    expect(await screen.findByText(/Credentials are hidden/)).toBeInTheDocument()
  })
})

/**
 * The Helm pages used to gate every action on `isAdmin` while the other twenty
 * resource pages used `useCanWrite` — cluster access plus whatever the caller's
 * own kubeconfig allows. The backend was always the looser of the two, so a
 * USER could install through the API but had no button. These lock in the
 * alignment, and the one place it does not apply.
 */
describe('HelmReleasesPage — a USER on their own cluster', () => {
  function asUser() {
    mockGet(mockApi, {
      '/auth/me': { username: 'bob', role: 'USER' },
      '/config': { privilegedFeatures: true },
      [BASE]: [RELEASE],
      [`${BASE}/grafana`]: DETAIL_FROM_STORED_CHART,
      [`${BASE}/grafana/reveal`]: DETAIL_REVEALED,
      [`${BASE}/grafana/history`]: HISTORY,
      [`${BASE}/grafana/chart-update`]: NO_REPO_LINKED,
      '/clusters/7/helm/repos': [],
      '/clusters': [{ id: 7, name: 'staging', status: 'APPROVED', builtIn: false }],
      '/clusters/7/namespaces': [{ name: 'monitoring' }],
    })
  }

  it('can act on releases their kubeconfig lets them touch', async () => {
    asUser()
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))

    // Cluster 7 is one they registered themselves, so writes are theirs to try;
    // the cluster's RBAC still has the final say server-side.
    expect(await screen.findByRole('button', { name: 'Uninstall' })).toBeInTheDocument()
  })

  it('still cannot reveal credentials — that stays ADMIN-only, as on the backend', async () => {
    asUser()
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await screen.findByRole('textbox')

    expect(screen.queryByRole('button', { name: 'Reveal' })).not.toBeInTheDocument()
    expect(screen.getByText(/An admin can reveal them/)).toBeInTheDocument()
  })

})

/**
 * Adding a repository is no longer the price of admission — it buys a version
 * notice and nothing else is gated on it.
 */
describe('HelmReleasesPage — chart version updates', () => {
  function withUpdateAvailable() {
    mockGet(mockApi, {
      '/auth/me': { username: 'admin', role: 'ADMIN' },
      [BASE]: [RELEASE],
      [`${BASE}/grafana`]: DETAIL_FROM_STORED_CHART,
      [`${BASE}/grafana/reveal`]: DETAIL_REVEALED,
      [`${BASE}/grafana/chart-update`]: UPDATE_AVAILABLE,
      '/clusters/7/helm/repos': [],
      '/clusters': [{ id: 7, name: 'staging', status: 'APPROVED', builtIn: false }],
      '/clusters/7/namespaces': [{ name: 'monitoring' }],
    })
  }

  it('says nothing when no repository is linked', async () => {
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await screen.findByRole('textbox')

    expect(screen.queryByText(/Newer chart version available/)).not.toBeInTheDocument()
  })

  it('offers the newer version when a linked repository has one', async () => {
    withUpdateAvailable()
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))

    expect(await screen.findByText(/Newer chart version available/)).toBeInTheDocument()
    expect(screen.getByText('11.2.0')).toBeInTheDocument()
  })

  it('upgrades to that version after confirming', async () => {
    withUpdateAvailable()
    renderPage()
    await userEvent.click(await screen.findByText('grafana'))
    await userEvent.click(await screen.findByRole('button', { name: 'Upgrade' }))

    // The dialog warns that a chart version change is not just a values edit.
    expect(await screen.findByText(/add, rename or remove resources/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Upgrade chart' }))

    await waitFor(() => expect(mockApi.post).toHaveBeenCalledWith(
      `${BASE}/grafana/chart-version`, { version: '11.2.0' }))
  })
})

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
