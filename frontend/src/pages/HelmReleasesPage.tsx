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
import type { HelmChart, HelmRelease, HelmReleaseDetail, HelmRepo } from '../types/k8s'

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
  const [tab, setTab] = useState<'values' | 'manifest' | 'notes'>('values')
  const [editedValues, setEditedValues] = useState('')
  const [upgrading, setUpgrading] = useState(false)
  const [linkRepo, setLinkRepo] = useState('')
  const [linkChartRef, setLinkChartRef] = useState('')
  const [linking, setLinking] = useState(false)
  const showNsColumn = ns === 'all'

  const { data: detail, isLoading: detailLoading } = useQuery<HelmReleaseDetail>({
    queryKey: ['helm-release-detail', clusterId, selected?.namespace, selected?.name],
    queryFn: async () => (await api.get<HelmReleaseDetail>(
      `/clusters/${clusterId}/namespaces/${selected!.namespace}/helm/releases/${selected!.name}`)).data,
    enabled: !!selected,
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

  const saveAndUpgrade = async () => {
    if (!selected || !detail?.chartRef) return
    setUpgrading(true)
    try {
      await api.post(`/clusters/${clusterId}/namespaces/${selected.namespace}/helm/install`, {
        releaseName: selected.name,
        chartRef: detail.chartRef,
        valuesYaml: editedValues,
      })
      toast.success(`"${selected.name}" upgraded`)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
      queryClient.invalidateQueries({ queryKey: ['helm-release-detail', clusterId, selected.namespace, selected.name] })
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Upgrade failed'))
    } finally {
      setUpgrading(false)
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
            <Tr key={`${r.namespace}/${r.name}`} onClick={() => { setSelected(r); setTab('values') }}
                highlighted={selected?.name === r.name && selected?.namespace === r.namespace}>
              <Td className="font-medium text-gray-900 dark:text-neutral-100">{r.name}</Td>
              {showNsColumn && <Td className="text-gray-500 dark:text-neutral-400">{r.namespace}</Td>}
              <Td><StatusBadge status={STATUS_MAP[r.status] ?? r.status} /></Td>
              <Td className="text-gray-500 dark:text-neutral-400">{r.chart}</Td>
              <Td className="hidden md:table-cell text-gray-500 dark:text-neutral-400">{r.appVersion || '—'}</Td>
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
            <DrawerRow label="App version" value={selected.appVersion || '—'} />
            <DrawerRow label="Revision" value={selected.revision} />
            <DrawerRow label="Updated" value={selected.updated} />

            <DrawerSection title="Details" />
            <div className="flex gap-1 mb-2">
              {(['values', 'manifest', 'notes'] as const).map((t) => (
                <button
                  key={t}
                  onClick={() => setTab(t)}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium capitalize transition-colors ${
                    tab === t ? 'bg-blue-600 text-white' : 'text-gray-600 dark:text-neutral-400 hover:bg-gray-100 dark:hover:bg-neutral-700'}`}
                >
                  {t}
                </button>
              ))}
            </div>
            {detailLoading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading…</p>}

            {detail && tab === 'values' && (
              <>
                {isAdmin && !detail.chartRef && (
                  <div className="rounded-md border border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10 px-2.5 py-2 mb-1.5">
                    <p className="text-[11px] text-amber-700 dark:text-amber-400 mb-1.5">
                      Couldn't auto-detect this release's chart — either its name doesn't match any added repo, or it
                      matches more than one, so editing is disabled until you pick the right one below.
                    </p>
                    <div className="flex gap-1.5">
                      <select
                        value={linkRepo}
                        onChange={(e) => { setLinkRepo(e.target.value); setLinkChartRef('') }}
                        className="flex-1 rounded border border-amber-300 bg-white dark:bg-neutral-900 px-2 py-1 text-xs
                                   focus:outline-none focus:ring-1 focus:ring-amber-500"
                      >
                        <option value="">Repo…</option>
                        {(repos ?? []).map((r) => <option key={r.name} value={r.name}>{r.name}</option>)}
                      </select>
                      <select
                        value={linkChartRef}
                        onChange={(e) => setLinkChartRef(e.target.value)}
                        disabled={!linkRepo}
                        className="flex-1 rounded border border-amber-300 bg-white dark:bg-neutral-900 px-2 py-1 text-xs
                                   focus:outline-none focus:ring-1 focus:ring-amber-500 disabled:opacity-50"
                      >
                        <option value="">Chart…</option>
                        {(repoCharts ?? []).map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
                      </select>
                      <button
                        onClick={linkChart}
                        disabled={linking || !linkChartRef}
                        className="shrink-0 rounded bg-amber-600 px-2.5 py-1 text-xs font-medium text-white
                                   hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                      >
                        {linking ? 'Linking…' : 'Link'}
                      </button>
                    </div>
                    {repos && repos.length === 0 && (
                      <p className="text-[10px] text-amber-600 dark:text-amber-400 mt-1">No repos added yet — add one on the Charts page first.</p>
                    )}
                  </div>
                )}
                <textarea
                  value={editedValues}
                  onChange={(e) => setEditedValues(e.target.value)}
                  readOnly={!isAdmin || !detail.chartRef}
                  spellCheck={false}
                  rows={16}
                  className="w-full rounded-lg border border-gray-200 bg-gray-950 text-gray-100 font-mono text-xs
                             leading-5 p-3 resize-none focus:outline-none disabled:opacity-60"
                />
                {isAdmin && detail.chartRef && (
                  <div className="flex items-center justify-between mt-2">
                    <button
                      onClick={() => setEditedValues(detail.values)}
                      disabled={!dirty || upgrading}
                      className="text-xs text-gray-500 dark:text-neutral-400 hover:text-gray-700 dark:hover:text-neutral-200 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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

            {detail && tab !== 'values' && (
              <pre className="rounded-lg border border-gray-200 bg-gray-950 text-gray-100 font-mono text-xs
                               leading-5 p-3 whitespace-pre-wrap break-all max-h-96 overflow-y-auto">
                {detail[tab] || `(no ${tab})`}
              </pre>
            )}
          </>
        )}
      </DetailDrawer>

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
