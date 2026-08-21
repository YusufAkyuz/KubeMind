import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { ChangeEffectNotice } from './ChangeEffectNotice'
import { renderWithProviders } from '../test/renderWithProviders'
import { mockGet } from '../test/mockApi'

const { mockApi } = vi.hoisted(() => ({
  mockApi: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}))
vi.mock('../api/client', () => ({ api: mockApi, apiErrorMessage: () => 'error' }))

const EFFECT_ON_POD = {
  action: 'EDIT_RESOURCE_YAML',
  resourceRef: 'Deployment/dev-team/api',
  username: 'yusuf',
  changedAt: '2026-08-21T18:41:00Z',
  warningSignature: 'Pod/dev-team/api-55cdd48788-5w5vx',
  warningReason: 'CrashLoopBackOff',
  minutesAfter: 4,
  matchLevel: 'OWNED_OBJECT' as const,
}
const HELM_EFFECT = {
  ...EFFECT_ON_POD,
  action: 'UPGRADE_HELM_VALUES',
  resourceRef: 'HelmRelease/dev-team/payments',
}

function insights(changeEffects: unknown[]) {
  return {
    available: true, nodeCount: 1, nodeVersions: [], namespaceCount: 1, podCount: 1,
    unhealthyPodCount: 0, unhealthyHighlights: [], incidentNarrative: null,
    topIncidents: [], recentChanges: [], changeEffects, lastUpdated: null,
  }
}

beforeEach(() => {
  mockApi.get.mockReset()
})

/**
 * The notice exists so the "what changed?" answer meets someone where they are
 * already looking — inside the drawer of the thing that is broken.
 */
describe('ChangeEffectNotice', () => {
  it('shows the change when the drawer is open on the workload that was changed', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([EFFECT_ON_POD]) })
    renderWithProviders(<ChangeEffectNotice clusterId="7" resourceRef="Deployment/dev-team/api" />)

    expect(await screen.findByText(/followed by warnings here/)).toBeInTheDocument()
    expect(screen.getByText(/started 4 min after EDIT_RESOURCE_YAML/)).toBeInTheDocument()
    // The warning is on a pod, not on this deployment, so it has to be named.
    expect(screen.getByText('Pod/dev-team/api-55cdd48788-5w5vx')).toBeInTheDocument()
  })

  /** The pod is where someone lands from an alert; the change is on its owner. */
  it('also shows when the drawer is open on the pod that started warning', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([EFFECT_ON_POD]) })
    renderWithProviders(
      <ChangeEffectNotice clusterId="7" resourceRef="Pod/dev-team/api-55cdd48788-5w5vx" />)

    expect(await screen.findByText(/followed by warnings here/)).toBeInTheDocument()
  })

  it('renders nothing for a resource no change touched', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([EFFECT_ON_POD]) })
    renderWithProviders(<ChangeEffectNotice clusterId="7" resourceRef="Deployment/dev-team/unrelated" />)

    // The data arrived — the notice simply has nothing to say about this one.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith('/clusters/7/cluster-insights'))
    expect(screen.queryByText(/followed by warnings here/)).not.toBeInTheDocument()
  })

  /**
   * A Helm change is the one with a real undo. The link goes to the release's
   * history rather than offering a rollback here: this is a coincidence in
   * time, and a one-click undo beside it would invite acting on a guess.
   */
  it('offers a way back to the release when the change was a Helm upgrade', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([HELM_EFFECT]) })
    renderWithProviders(<ChangeEffectNotice clusterId="7" resourceRef="HelmRelease/dev-team/payments" />)

    const link = await screen.findByRole('link', { name: /Review this release's history/ })
    expect(link).toHaveAttribute('href', '/clusters/7/namespaces/dev-team/helm/releases')
    expect(screen.queryByRole('button', { name: /Roll ?back/i })).not.toBeInTheDocument()
  })

  it('does not offer a release link for a plain resource change', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([EFFECT_ON_POD]) })
    renderWithProviders(<ChangeEffectNotice clusterId="7" resourceRef="Deployment/dev-team/api" />)

    await screen.findByText(/followed by warnings here/)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  /** The wording is the product decision — it must never read as a verdict. */
  it('presents the link as timing rather than cause', async () => {
    mockGet(mockApi, { '/auth/me': { username: 'bob', role: 'USER' },
                       '/clusters/7/cluster-insights': insights([EFFECT_ON_POD]) })
    renderWithProviders(<ChangeEffectNotice clusterId="7" resourceRef="Deployment/dev-team/api" />)

    expect(await screen.findByText(/Timing only/)).toBeInTheDocument()
    expect(screen.queryByText(/caused by/i)).not.toBeInTheDocument()
  })
})
