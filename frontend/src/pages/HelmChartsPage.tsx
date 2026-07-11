import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { PageHeader } from '../components/PageHeader'
import { Table, Tr, Td } from '../components/Table'
import { Modal } from '../components/Modal'
import { ErrorBanner } from '../components/ErrorBanner'
import { useToast } from '../components/Toast'
import { useAuth } from '../auth/AuthContext'
import { IconPlus, IconSearch, IconX } from '../components/Icons'
import type { HelmChart, HelmRepo } from '../types/k8s'

export function HelmChartsPage() {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const { isAdmin } = useAuth()
  const toast = useToast()
  const queryClient = useQueryClient()

  const [query, setQuery] = useState('')
  const [searchTerm, setSearchTerm] = useState('')
  const [addRepoOpen, setAddRepoOpen] = useState(false)
  const [repoName, setRepoName] = useState('')
  const [repoUrl, setRepoUrl] = useState('')
  const [installing, setInstalling] = useState<HelmChart | null>(null)
  const [releaseName, setReleaseName] = useState('')
  const [valuesYaml, setValuesYaml] = useState('')
  const [valuesLoading, setValuesLoading] = useState(false)
  const [installBusy, setInstallBusy] = useState(false)

  const noNamespace = ns === '_'

  const { data: repos, isLoading: reposLoading } = useQuery<HelmRepo[]>({
    queryKey: ['helm-repos', clusterId],
    queryFn: async () => (await api.get<HelmRepo[]>(`/clusters/${clusterId}/helm/repos`)).data,
    enabled: !!clusterId,
  })

  const { data: charts, isLoading: chartsLoading, isError, error } = useQuery<HelmChart[]>({
    queryKey: ['helm-charts', clusterId, searchTerm],
    queryFn: async () => (await api.get<HelmChart[]>(
      `/clusters/${clusterId}/helm/charts/search`, { params: { q: searchTerm } })).data,
    enabled: !!clusterId && (repos?.length ?? 0) > 0,
  })

  const addRepo = async () => {
    if (!repoName.trim() || !repoUrl.trim()) return
    try {
      await api.post(`/clusters/${clusterId}/helm/repos`, { name: repoName.trim(), url: repoUrl.trim() })
      toast.success(`Repo "${repoName}" added`)
      setAddRepoOpen(false)
      setRepoName('')
      setRepoUrl('')
      queryClient.invalidateQueries({ queryKey: ['helm-repos', clusterId] })
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Could not add repo'))
    }
  }

  const removeRepo = async (name: string) => {
    try {
      await api.delete(`/clusters/${clusterId}/helm/repos/${name}`)
      toast.success(`Repo "${name}" removed`)
      queryClient.invalidateQueries({ queryKey: ['helm-repos', clusterId] })
      queryClient.invalidateQueries({ queryKey: ['helm-charts', clusterId] })
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Could not remove repo'))
    }
  }

  const openInstall = async (chart: HelmChart) => {
    setInstalling(chart)
    setReleaseName(chart.name.split('/')[1] ?? chart.name)
    setValuesYaml('')
    setValuesLoading(true)
    try {
      const res = await api.get<string>(`/clusters/${clusterId}/helm/charts/values`, {
        params: { chartRef: chart.name },
        responseType: 'text',
        transformResponse: [(data) => data], // keep raw YAML string, skip JSON parse
      })
      setValuesYaml(res.data)
    } catch (e) {
      toast.error(apiErrorMessage(e, "Could not download this chart's default values"))
    } finally {
      setValuesLoading(false)
    }
  }

  const install = async () => {
    if (!installing || !releaseName.trim() || !ns || noNamespace) return
    setInstallBusy(true)
    try {
      await api.post(`/clusters/${clusterId}/namespaces/${ns}/helm/install`, {
        releaseName: releaseName.trim(),
        chartRef: installing.name,
        valuesYaml: valuesYaml.trim() || undefined,
      })
      toast.success(`"${releaseName}" installed`)
      setInstalling(null)
      queryClient.invalidateQueries({ queryKey: ['helm/releases', clusterId, ns] })
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Install failed'))
    } finally {
      setInstallBusy(false)
    }
  }

  return (
    <Layout>
      <PageHeader title="Helm Charts" subtitle="search repos and install" />

      {/* Repos */}
      <div className="rounded-xl border border-gray-200 bg-white p-4 mb-4">
        <div className="flex items-center justify-between mb-2">
          <p className="text-sm font-medium text-gray-900">Repositories</p>
          {isAdmin && (
            <button
              onClick={() => setAddRepoOpen(true)}
              className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-800 transition-colors"
            >
              <IconPlus className="w-3.5 h-3.5" /> Add repo
            </button>
          )}
        </div>
        {reposLoading && <p className="text-xs text-gray-400">Loading…</p>}
        {repos && repos.length === 0 && (
          <p className="text-xs text-gray-400">No repos added yet — add one to search for charts.</p>
        )}
        {repos && repos.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {repos.map((r) => (
              <span key={r.name} className="inline-flex items-center gap-1.5 rounded-full border border-gray-200
                                             bg-gray-50 px-2.5 py-1 text-xs text-gray-600">
                <span className="font-medium">{r.name}</span>
                <span className="text-gray-400">{r.url}</span>
                {isAdmin && (
                  <button onClick={() => removeRepo(r.name)} className="text-gray-400 hover:text-red-600 transition-colors">
                    <IconX className="w-3 h-3" />
                  </button>
                )}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Search */}
      <div className="flex items-center gap-2 mb-4">
        <div className="relative flex-1 max-w-md">
          <IconSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') setSearchTerm(query.trim()) }}
            placeholder="Search charts, e.g. nginx"
            className="w-full rounded-lg border border-gray-300 pl-9 pr-3 py-2 text-sm
                       focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
        </div>
        <button
          onClick={() => setSearchTerm(query.trim())}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          Search
        </button>
      </div>

      {noNamespace && (
        <p className="text-xs text-amber-600 mb-2">Select a namespace from the sidebar to install charts into.</p>
      )}
      {(repos?.length ?? 0) === 0 && !reposLoading && (
        <p className="text-sm text-gray-400">Add a chart repository above to start searching.</p>
      )}
      {chartsLoading && <p className="text-sm text-gray-400">Searching…</p>}
      {isError && <ErrorBanner message={`Search failed: ${(error as Error).message}`} />}

      {charts && charts.length === 0 && !chartsLoading && (repos?.length ?? 0) > 0 && (
        <p className="text-sm text-gray-400">No charts found{searchTerm ? ` for "${searchTerm}"` : ''}.</p>
      )}

      {charts && charts.length > 0 && (
        <Table columns={[
          { key: 'name', label: 'Chart' },
          { key: 'version', label: 'Version' },
          { key: 'appVersion', label: 'App version', className: 'hidden md:table-cell' },
          { key: 'description', label: 'Description', className: 'hidden lg:table-cell' },
          { key: 'actions', label: '' },
        ]}>
          {charts.map((c) => (
            <Tr key={c.name + c.version}>
              <Td className="font-medium text-gray-900 font-mono text-xs">{c.name}</Td>
              <Td className="text-gray-500 tabular-nums">{c.version}</Td>
              <Td className="hidden md:table-cell text-gray-500">{c.appVersion || '—'}</Td>
              <Td className="hidden lg:table-cell text-gray-500 max-w-md truncate">{c.description}</Td>
              <Td>
                {isAdmin && (
                  <button
                    onClick={() => openInstall(c)}
                    disabled={noNamespace}
                    className="rounded-md bg-blue-600 px-2.5 py-1 text-xs font-medium text-white
                               hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  >
                    Install
                  </button>
                )}
              </Td>
            </Tr>
          ))}
        </Table>
      )}

      {/* Add repo modal */}
      <Modal open={addRepoOpen} title="Add chart repository" onClose={() => setAddRepoOpen(false)}>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Name</label>
            <input
              type="text" value={repoName} onChange={(e) => setRepoName(e.target.value)}
              placeholder="bitnami"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">URL</label>
            <input
              type="text" value={repoUrl} onChange={(e) => setRepoUrl(e.target.value)}
              placeholder="https://charts.bitnami.com/bitnami"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <button onClick={() => setAddRepoOpen(false)}
                    className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 transition-colors">
              Cancel
            </button>
            <button onClick={addRepo} disabled={!repoName.trim() || !repoUrl.trim()}
                    className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white
                               hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
              Add
            </button>
          </div>
        </div>
      </Modal>

      {/* Install modal */}
      <Modal open={!!installing} title={`Install ${installing?.name ?? ''}`} onClose={() => setInstalling(null)} wide>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">Release name</label>
            <input
              type="text" value={releaseName} onChange={(e) => setReleaseName(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          </div>
          <p className="text-xs text-gray-400">Namespace: <span className="font-medium text-gray-600">{ns}</span></p>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">
              Values (YAML) {valuesLoading && <span className="text-gray-400 font-normal">— downloading chart defaults…</span>}
            </label>
            <textarea
              value={valuesYaml} onChange={(e) => setValuesYaml(e.target.value)}
              rows={16} spellCheck={false} disabled={valuesLoading}
              placeholder={valuesLoading ? '' : "# this chart defines no values"}
              className="w-full rounded-md border border-gray-300 bg-gray-950 text-gray-100 font-mono text-xs
                         leading-5 p-3 resize-none focus:outline-none disabled:opacity-60"
            />
          </div>
          <p className="text-[10px] text-gray-400">
            Runs <code className="font-mono">helm upgrade --install</code> on the backend — creates the release if it
            doesn't exist yet, upgrades it in place if it does.
          </p>
          <div className="flex justify-end gap-2 pt-1">
            <button onClick={() => setInstalling(null)}
                    className="rounded-md border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 transition-colors">
              Cancel
            </button>
            <button onClick={install} disabled={installBusy || !releaseName.trim()}
                    className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white
                               hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
              {installBusy ? 'Installing…' : 'Install'}
            </button>
          </div>
        </div>
      </Modal>
    </Layout>
  )
}
