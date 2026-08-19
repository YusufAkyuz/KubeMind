import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td, withNamespaceColumn } from '../components/Table'
import { StatusBadge } from '../components/StatusBadge'
import { DetailDrawer, DrawerRow, DrawerSection } from '../components/DetailDrawer'
import { ErrorBanner, EmptyState } from '../components/ErrorBanner'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { useNamespacedList, noNamespaceMessage } from '../hooks/useNamespacedList'
import { useAuth } from '../auth/AuthContext'
import type { HelmChart, HelmChartUpdate, HelmRelease, HelmReleaseDetail, HelmRepo, HelmRevision } from '../types/k8s'

const COLUMNS = [
  { key: 'name', label: 'Name' },
  { key: 'status', label: 'Status' },
  { key: 'chart', label: 'Chart' },
  { key: 'appVersion', label: 'App version', className: 'hidden md:table-cell' },
  { key: 'revision', label: 'Rev', className: 'hidden lg:table-cell' },
  { key: 'updated', label: 'Updated', className: 'hidden lg:table-cell' },
]

// Helm's own status vocabulary → StatusBadge's (deployed/failed/pending-* etc.).
const STATUS_MAP: Record<string, string> = {
  deployed: 'Ready', failed: 'Failed', uninstalled: 'Failed',
  'pending-install': 'Pending', 'pending-upgrade': 'Pending', 'pending-rollback': 'Pending',
}

export function HelmReleasesPage() {
  const { clusterId, ns, noNamespace, data, isLoading, isError, error } = useNamespacedList<HelmRelease>('helm/releases')
  const { isAdmin } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<HelmRelease | null>(null)
  const [uninstallOpen, setUninstallOpen] = useState(false)
  const [tab, setTab] = useState<'values' | 'history' | 'manifest' | 'notes'>('values')
  const [rollbackTo, setRollbackTo] = useState<HelmRevision | null>(null)
  const [chartUpgradeOpen, setChartUpgradeOpen] = useState(false)
  /** Reset per release: revealing one release's credentials must not reveal the next one's. */
  const [revealed, setRevealed] = useState(false)
  const [editedValues, setEditedValues] = useState('')
  const [upgrading, setUpgrading] = useState(false)
  const [linkRepo, setLinkRepo] = useState('')
  const [linkChartRef, setLinkChartRef] = useState('')
  const [linking, setLinking] = useState(false)
  /** Chart pickers stay folded until asked for, unless picking one is the only way forward. */
  const [linkOpen, setLinkOpen] = useState(false)
  const showNsColumn = ns === 'all'

  // Credentials arrive masked. Revealing is a separate, ADMIN-only, audited
  // request — the same bar the Secrets page sets for the same data — so it is
  // kept out of this query rather than folded in behind a flag.
  const { data: detail, isLoading: detailLoading } = useQuery<HelmReleaseDetail>({
    queryKey: ['helm-release-detail', clusterId, selected?.namespace, selected?.name, revealed],
    queryFn: async () => (await api.get<HelmReleaseDetail>(
      `/clusters/${clusterId}/namespaces/${selected!.namespace}/helm/releases/${selected!.name}`
      + (revealed ? '/reveal' : ''))).data,
    enabled: !!selected,
  })

  // Fetched only once the tab is opened: a revision log is rarely what someone
  // came for, and it is one more `helm` process per release otherwise.
  const { data: history, isLoading: historyLoading, isError: historyError } = useQuery<HelmRevision[]>({
    queryKey: ['helm-release-history', clusterId, selected?.namespace, selected?.name],
    queryFn: async () => (await api.get<HelmRevision[]>(
      `/clusters/${clusterId}/namespaces/${selected!.namespace}/helm/releases/${selected!.name}/history`)).data,
    enabled: !!selected && tab === 'history',
  })

  // The only thing a chart repository is still needed for. Separate from `detail`
  // so a slow or unreachable repo never delays showing the release itself.
  const { data: chartUpdate } = useQuery<HelmChartUpdate>({
    queryKey: ['helm-chart-update', clusterId, selected?.namespace, selected?.name],
    queryFn: async () => (await api.get<HelmChartUpdate>(
      `/clusters/${clusterId}/namespaces/${selected!.namespace}/helm/releases/${selected!.name}/chart-update`)).data,
    enabled: !!selected,
    staleTime: 60_000,
  })

  // Repo + chart pickers for the "link an externally-installed release" flow — the user
  // always picks from a list, never types a repo/chart reference by hand (that's what led
  // to someone pasting a repo *URL* into a chart-reference field and helm trying to fetch
  // an HTML page as a chart archive).
  const { data: repos } = useQuery<HelmRepo[]>({
    queryKey: ['helm-repos', clusterId],
    queryFn: async () => (await api.get<HelmRepo[]>(`/clusters/${clusterId}/helm/repos`)).data,
    enabled: !!selected && isAdmin,
  })
  const { data: repoCharts } = useQuery<HelmChart[]>({
    queryKey: ['helm-charts-for-repo', clusterId, linkRepo],
    queryFn: async () => (await api.get<HelmChart[]>(
      `/clusters/${clusterId}/helm/charts/search`, { params: { q: `${linkRepo}/` } })).data,
    enabled: !!linkRepo,
  })

  // Reset the editor to the server's current values whenever a different release is opened.
  useEffect(() => { setEditedValues(detail?.values ?? '') }, [detail?.values])

  const dirty = detail != null && editedValues !== detail.values

  const uninstall = async () => {
    if (!selected) return
    try {
      await api.delete(`/clusters/${clusterId}/namespaces/${selected.namespace}/helm/releases/${selected.name}`)
      toast.success(`Release "${selected.name}" uninstalled`)
      setSelected(null)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Uninstall failed'))
    }
  }

  // Posts only the values: the backend works out which chart the release is
  // already running — normally the one Helm stored in the cluster, which is also
  // what stops a values edit from quietly moving the release to a newer chart
  // version the way upgrading against a repo reference does.
  const saveAndUpgrade = async () => {
    if (!selected || !detail?.valuesEditable) return
    setUpgrading(true)
    try {
      await api.post(
        `/clusters/${clusterId}/namespaces/${selected.namespace}/helm/releases/${selected.name}/values`,
        { valuesYaml: editedValues },
      )
      toast.success(`"${selected.name}" upgraded`)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
      queryClient.invalidateQueries({ queryKey: ['helm-release-detail', clusterId, selected.namespace, selected.name] })
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Upgrade failed'))
    } finally {
      setUpgrading(false)
    }
  }

  /**
   * Unlike "Save & Upgrade", this needs no chartRef — Helm replays the chart it
   * stored with that revision. It is the one repair action available on a
   * release installed outside KubeMind.
   */
  const rollback = async () => {
    if (!selected || !rollbackTo) return
    try {
      await api.post(
        `/clusters/${clusterId}/namespaces/${selected.namespace}/helm/releases/${selected.name}/rollback`,
        { revision: rollbackTo.revision },
      )
      toast.success(`"${selected.name}" rolled back to revision ${rollbackTo.revision}`)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
      queryClient.invalidateQueries({ queryKey: ['helm-release-detail', clusterId, selected.namespace, selected.name] })
      queryClient.invalidateQueries({ queryKey: ['helm-release-history', clusterId, selected.namespace, selected.name] })
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Rollback failed'))
    }
  }

  /** Keeps the release's current values — the backend re-supplies them, since a
   *  bare `helm upgrade` would reset the release to the chart's defaults. */
  const upgradeChart = async () => {
    if (!selected || !chartUpdate?.latestVersion) return
    try {
      await api.post(
        `/clusters/${clusterId}/namespaces/${selected.namespace}/helm/releases/${selected.name}/chart-version`,
        { version: chartUpdate.latestVersion },
      )
      toast.success(`"${selected.name}" upgraded to chart ${chartUpdate.latestVersion}`)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
      queryClient.invalidateQueries({ queryKey: ['helm-release-detail', clusterId, selected.namespace, selected.name] })
      queryClient.invalidateQueries({ queryKey: ['helm-chart-update', clusterId, selected.namespace, selected.name] })
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Chart upgrade failed'))
    }
  }

  const linkChart = async () => {
    if (!selected || !linkChartRef.trim()) return
    setLinking(true)
    try {
      await api.post(`/clusters/${clusterId}/namespaces/${selected.namespace}/helm/releases/${selected.name}/chart-ref`, {
        chartRef: linkChartRef.trim(),
      })
      toast.success('Chart reference linked — values editing unlocked')
      setLinkRepo('')
      setLinkChartRef('')
      setLinkOpen(false)
      queryClient.invalidateQueries({ queryKey: ['helm-release-detail', clusterId, selected.namespace, selected.name] })
    } catch (e) {
      toast.error(apiErrorMessage(e, "Could not link — check the chart reference is correct and its repo is added"))
    } finally {
      setLinking(false)
    }
  }

  return (
    <Layout>
      <PageHeader title="Helm Releases"
                  subtitle={ns === 'all' ? 'All namespaces' : ns && ns !== '_' ? `namespace: ${ns}` : undefined}
                  count={data?.length} noun="release" />

      {noNamespace && <EmptyState message={noNamespaceMessage('helm releases')} />}
      {isLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
      {isError && <ErrorBanner message={`Could not load releases: ${(error as Error).message}`} />}

      {data && data.length === 0 && !isLoading && (
        <div className="rounded-xl border border-dashed border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-6 py-12 text-center">
          <p className="text-sm text-gray-400 dark:text-neutral-500">No Helm releases in this namespace.</p>
        </div>
      )}

      {data && data.length > 0 && (
        <Table columns={withNamespaceColumn(COLUMNS, showNsColumn)}>
          {data.map((r) => (
            <Tr key={`${r.namespace}/${r.name}`} onClick={() => { setSelected(r); setTab('values'); setRevealed(false) }}
                highlighted={selected?.name === r.name && selected?.namespace === r.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{r.name}</Td>
              {showNsColumn && <Td className="text-neutral-500 dark:text-neutral-400">{r.namespace}</Td>}
              <Td><StatusBadge status={STATUS_MAP[r.status] ?? r.status} /></Td>
              <Td className="text-neutral-500 dark:text-neutral-400">{r.chart}</Td>
              <Td className="hidden md:table-cell text-neutral-500 dark:text-neutral-400">{r.appVersion || '—'}</Td>
              <Td className="hidden lg:table-cell text-gray-400 dark:text-neutral-500 tabular-nums">{r.revision}</Td>
              <Td className="hidden lg:table-cell text-gray-400 dark:text-neutral-500 text-xs">{r.updated}</Td>
            </Tr>
          ))}
        </Table>
      )}

      <DetailDrawer open={!!selected} title={selected?.name ?? ''} subtitle={`Helm release · ${selected?.namespace ?? ns}`}
                    onClose={() => setSelected(null)}>
        {selected && (
          <>
            {isAdmin && (
              <div className="pb-3 flex flex-wrap gap-2">
                <button
                  onClick={() => setUninstallOpen(true)}
                  className="rounded-md border border-red-200 dark:border-red-500/30 px-3 py-1.5 text-xs font-medium
                             text-red-600 dark:text-red-400 hover:bg-red-50 transition-colors"
                >
                  Uninstall
                </button>
              </div>
            )}

            <DrawerSection title="Overview" />
            <DrawerRow label="Status" value={<StatusBadge status={STATUS_MAP[selected.status] ?? selected.status} />} />
            <DrawerRow label="Chart" value={selected.chart} />
            {/* Registering a repo used to be the price of editing a release at all.
                It buys exactly this now, and nothing is blocked without it. */}
            {chartUpdate?.updateAvailable && (
              <div className="my-1.5 flex items-center gap-2 rounded-md border border-blue-200 dark:border-blue-500/30
                              bg-blue-50 dark:bg-blue-500/10 px-2.5 py-2">
                <p className="flex-1 text-[11px] text-blue-700 dark:text-blue-300">
                  Newer chart version available:{' '}
                  <span className="font-mono">{chartUpdate.currentVersion}</span> →{' '}
                  <span className="font-mono font-semibold">{chartUpdate.latestVersion}</span>
                </p>
                {isAdmin && (
                  <button
                    onClick={() => setChartUpgradeOpen(true)}
                    className="shrink-0 rounded bg-blue-600 px-2.5 py-1 text-xs font-medium text-white
                               hover:bg-blue-700 transition-colors"
                  >
                    Upgrade
                  </button>
                )}
              </div>
            )}
            <DrawerRow label="App version" value={selected.appVersion || '—'} />
            <DrawerRow label="Revision" value={selected.revision} />
            <DrawerRow label="Updated" value={selected.updated} />

            <DrawerSection title="Details" />
            <div className="flex gap-1 mb-2">
              {(['values', 'history', 'manifest', 'notes'] as const).map((t) => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium capitalize transition-colors ${
                    tab === t ? 'bg-blue-600 text-white' : 'text-neutral-600 dark:text-neutral-400 hover:bg-gray-100 dark:hover:bg-neutral-700'}`}
                >
                  {t}
                </button>
              ))}
            </div>
            {detailLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}

            {/* Values and rendered Secrets are credentials; the Secrets page has
                always required ADMIN and an audit record to show them. Spelling
                out why the editor is read-only beats leaving it inert. */}
            {detail?.masked && (tab === 'values' || tab === 'manifest') && (
              <div className="flex items-center gap-2 rounded-md border border-gray-200 dark:border-neutral-700
                              bg-gray-50 dark:bg-neutral-800/60 px-2.5 py-2 mb-1.5">
                <p className="flex-1 text-[11px] text-gray-500 dark:text-neutral-400">
                  Credentials are hidden.{isAdmin ? ' Revealing them is recorded in the audit log, and editing needs them.' : ' An admin can reveal them.'}
                </p>
                {isAdmin && (
                  <button
                    onClick={() => setRevealed(true)}
                    className="shrink-0 rounded border border-gray-300 dark:border-neutral-600 px-2.5 py-1
                               text-[11px] font-medium text-gray-700 dark:text-neutral-300
                               hover:bg-gray-100 dark:hover:bg-neutral-700 transition-colors"
                  >
                    Reveal
                  </button>
                )}
              </div>
            )}

            {detail && tab === 'values' && (
              <>
                {/* Editing already works: say so in one muted line and keep the
                    pickers folded away. Three form controls sitting above the
                    editor read as a required step no matter what the copy says —
                    which is exactly how this landed the first time. */}
                {isAdmin && detail.valuesEditable && !chartUpdate?.chartRef && !linkOpen && (
                  <p className="text-[11px] text-gray-400 dark:text-neutral-500 mb-1.5">
                    Editing uses the chart stored in the cluster.{' '}
                    <button
                      onClick={() => setLinkOpen(true)}
                      className="underline underline-offset-2 hover:text-gray-600 dark:hover:text-neutral-300 transition-colors"
                    >
                      Link a chart
                    </button>{' '}
                    to be told about newer versions.
                  </p>
                )}

                {/* Nothing to upgrade against, so editing really is off until a
                    chart is picked — here the pickers are the point. */}
                {isAdmin && !chartUpdate?.chartRef && (!detail.valuesEditable || linkOpen) && (
                  <div className={`rounded-md border px-2.5 py-2 mb-1.5 ${
                    detail.valuesEditable
                      ? 'border-gray-200 dark:border-neutral-700 bg-gray-50 dark:bg-neutral-800/60'
                      : 'border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10'}`}>
                    <p className={`text-[11px] mb-1.5 ${
                      detail.valuesEditable
                        ? 'text-gray-500 dark:text-neutral-400'
                        : 'text-amber-700 dark:text-amber-400'}`}>
                      {detail.valuesEditable
                        ? 'Pick the repository chart this release came from — used only for version-update notices.'
                        : "This release's chart couldn't be recovered from the cluster (Helm doesn't store subcharts "
                          + "in full) and its name doesn't match exactly one added repo — pick the right chart to "
                          + 'enable editing.'}
                    </p>
                    <div className="flex gap-1.5">
                      <select
                        value={linkRepo}
                        onChange={(e) => { setLinkRepo(e.target.value); setLinkChartRef('') }}
                        className="flex-1 rounded border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-900
                                   text-gray-900 dark:text-neutral-100 px-2 py-1 text-xs
                                   focus:outline-none focus:ring-1 focus:ring-blue-500"
                      >
                        <option value="">Repo…</option>
                        {(repos ?? []).map((r) => <option key={r.name} value={r.name}>{r.name}</option>)}
                      </select>
                      <select
                        value={linkChartRef}
                        onChange={(e) => setLinkChartRef(e.target.value)}
                        disabled={!linkRepo}
                        className="flex-1 rounded border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-900
                                   text-gray-900 dark:text-neutral-100 px-2 py-1 text-xs
                                   focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:opacity-50"
                      >
                        <option value="">Chart…</option>
                        {(repoCharts ?? []).map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
                      </select>
                      <button
                        onClick={linkChart}
                        disabled={linking || !linkChartRef}
                        className="shrink-0 rounded bg-blue-600 px-2.5 py-1 text-xs font-medium text-white
                                   hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        {linking ? 'Linking…' : 'Link'}
                      </button>
                    </div>
                    {repos && repos.length === 0 && (
                      <p className="text-[10px] text-gray-400 dark:text-neutral-500 mt-1">No repos added yet — add one on the Charts page first.</p>
                    )}
                  </div>
                )}
                <textarea
                  value={editedValues}
                  onChange={(e) => setEditedValues(e.target.value)}
                  readOnly={!isAdmin || !detail.valuesEditable || detail.masked}
                  spellCheck={false}
                  rows={16}
                  className="w-full rounded-lg border border-neutral-800 bg-neutral-950 text-neutral-100 font-mono text-xs
                             leading-5 p-3 resize-none focus:outline-none disabled:opacity-60"
                />
                {isAdmin && detail.valuesEditable && !detail.masked && (
                  <div className="flex items-center justify-between mt-2">
                    <button
                      onClick={() => setEditedValues(detail.values)}
                      disabled={!dirty || upgrading}
                      className="text-xs text-neutral-500 dark:text-neutral-400 hover:text-gray-700 dark:hover:text-neutral-200 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                    >
                      Revert changes
                    </button>
                    <button
                      onClick={saveAndUpgrade}
                      disabled={!dirty || upgrading}
                      className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white
                                 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      {upgrading ? 'Upgrading…' : 'Save & Upgrade'}
                    </button>
                  </div>
                )}
              </>
            )}

            {tab === 'history' && (
              <>
                {historyLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}
                {historyError && <ErrorBanner message="Could not load this release's history." />}
                {history && (
                  <div className="rounded-lg border border-gray-200 dark:border-neutral-700 overflow-hidden">
                    {[...history].reverse().map((rev) => {
                      // Helm records a rollback as a new revision, so the newest row is
                      // always what is live — rolling back to it would be a no-op.
                      const isCurrent = rev.status === 'deployed'
                      return (
                        <div
                          key={rev.revision}
                          className="flex items-center gap-3 px-3 py-2 border-b last:border-b-0
                                     border-gray-100 dark:border-neutral-800"
                        >
                          <span className="w-8 shrink-0 text-xs tabular-nums text-gray-400 dark:text-neutral-500">
                            #{rev.revision}
                          </span>
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-xs text-gray-800 dark:text-neutral-200">{rev.description}</p>
                            <p className="truncate text-[11px] text-gray-400 dark:text-neutral-500">
                              {rev.chart} · {rev.updated}
                            </p>
                          </div>
                          <StatusBadge status={STATUS_MAP[rev.status] ?? rev.status} />
                          {isAdmin && (
                            <button
                              onClick={() => setRollbackTo(rev)}
                              disabled={isCurrent}
                              title={isCurrent ? 'Already the deployed revision' : undefined}
                              className="shrink-0 rounded-md border border-gray-300 dark:border-neutral-600 px-2 py-1
                                         text-[11px] font-medium text-gray-700 dark:text-neutral-300
                                         hover:bg-gray-50 dark:hover:bg-neutral-800
                                         disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                            >
                              {isCurrent ? 'Current' : 'Rollback'}
                            </button>
                          )}
                        </div>
                      )
                    })}
                  </div>
                )}
              </>
            )}

            {detail && (tab === 'manifest' || tab === 'notes') && (
              <pre className="rounded-lg border border-neutral-800 bg-neutral-950 text-neutral-100 font-mono text-xs
                               leading-5 p-3 whitespace-pre-wrap break-all max-h-96 overflow-y-auto">
                {detail[tab] || `(no ${tab})`}
              </pre>
            )}
          </>
        )}
      </DetailDrawer>

      <ConfirmDialog
        open={chartUpgradeOpen}
        title={`Upgrade chart to ${chartUpdate?.latestVersion ?? ''}`}
        message={
          <>
            Moves <span className="font-mono font-medium text-gray-800 dark:text-neutral-200">{selected?.name}</span> from
            chart <span className="font-mono">{chartUpdate?.currentVersion}</span> to{' '}
            <span className="font-mono">{chartUpdate?.latestVersion}</span>, keeping its current values.
            A new chart version can add, rename or remove resources — check its changelog first.
          </>
        }
        confirmLabel="Upgrade chart"
        danger
        onConfirm={upgradeChart}
        onClose={() => setChartUpgradeOpen(false)}
      />

      <ConfirmDialog
        open={!!rollbackTo}
        title={`Roll back to revision ${rollbackTo?.revision ?? ''}`}
        message={
          <>
            Re-applies the chart and values stored with revision{' '}
            <span className="font-medium">{rollbackTo?.revision}</span> of{' '}
            <span className="font-mono font-medium text-gray-800 dark:text-neutral-200">{selected?.name}</span>.
            Whatever is running now will be replaced. History is kept — the rollback becomes a new revision.
          </>
        }
        confirmLabel="Roll back"
        danger
        onConfirm={rollback}
        onClose={() => setRollbackTo(null)}
      />

      <ConfirmDialog
        open={uninstallOpen}
        title={`Uninstall ${selected?.name ?? ''}`}
        message={
          <>
            This will remove the Helm release <span className="font-mono font-medium text-gray-800 dark:text-neutral-200">{selected?.name}</span> and
            everything it deployed from namespace <span className="font-medium">{selected?.namespace}</span>.
          </>
        }
        confirmLabel="Uninstall"
        danger
        requireText={selected?.name}
        onConfirm={uninstall}
        onClose={() => setUninstallOpen(false)}
      />
    </Layout>
  )
}
