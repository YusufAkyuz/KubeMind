import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { IconChevronRight } from '../components/Icons'

const CLUSTER_ALLOWED_KINDS = ['ClusterRole', 'ClusterRoleBinding'] as const
type ClusterAllowedKind = (typeof CLUSTER_ALLOWED_KINDS)[number]

const CLUSTER_KIND_ROUTES: Record<string, string> = {
  ClusterRole: 'clusterroles',
  ClusterRoleBinding: 'clusterrolebindings',
}

const CLUSTER_KIND_TEMPLATES: Record<ClusterAllowedKind, string> = {
  ClusterRole: `apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: my-cluster-role
rules:
  - apiGroups: [""]
    resources: ["nodes"]
    verbs: ["get", "list", "watch"]
`,
  ClusterRoleBinding: `apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: my-cluster-role-binding
subjects:
  - kind: ServiceAccount
    name: my-service-account
    namespace: default
roleRef:
  kind: ClusterRole
  name: my-cluster-role
  apiGroup: rbac.authorization.k8s.io
`,
}

/** Loose client-side preview only — the server validates authoritatively. */
function extractPreview(yaml: string): { kind: string; name: string } {
  const kind = yaml.match(/^kind:\s*(\S+)/m)?.[1] ?? 'resource'
  const name = yaml.match(/^\s*name:\s*(\S+)/m)?.[1] ?? 'unnamed'
  return { kind, name }
}

/**
 * Minimal counterpart to CreateResourcePage for cluster-scoped kinds (no
 * namespace segment in the route, so it can't reuse that page's AI draft/
 * analyze flow — those endpoints are namespace-scoped). Plain YAML + Create,
 * no AI assist here; add it later if this earns its keep.
 */
export function CreateClusterResourcePage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const [searchParams] = useSearchParams()
  const kindHint = searchParams.get('kind')
  const navigate = useNavigate()
  const toast = useToast()

  const [yaml, setYaml] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)

  useEffect(() => {
    if (yaml) return
    if (kindHint && (CLUSTER_ALLOWED_KINDS as readonly string[]).includes(kindHint)) {
      setYaml(CLUSTER_KIND_TEMPLATES[kindHint as ClusterAllowedKind])
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kindHint])

  const create = async () => {
    if (!clusterId) return
    try {
      const res = await api.post<{ kind: string; name: string }>(
        `/clusters/${clusterId}/resources`,
        { yaml },
      )
      toast.success(`${res.data.kind} "${res.data.name}" created`)
      const route = CLUSTER_KIND_ROUTES[res.data.kind]
      navigate(route ? `/clusters/${clusterId}/${route}` : `/clusters/${clusterId}/nodes`)
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Create failed'))
    }
  }

  const preview = extractPreview(yaml)

  return (
    <Layout>
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 mb-4 flex-wrap">
        <span>cluster-scoped</span>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600 font-medium">Create resource</span>
      </nav>

      <div className="rounded-xl border border-gray-200 bg-white shadow-sm overflow-hidden max-w-3xl">
        {!yaml && (
          <div className="px-4 py-3 border-b border-gray-200">
            <p className="text-xs font-medium text-gray-500 mb-2">Start from a template</p>
            <div className="flex flex-wrap gap-1.5">
              {CLUSTER_ALLOWED_KINDS.map((kind) => (
                <button
                  key={kind}
                  onClick={() => setYaml(CLUSTER_KIND_TEMPLATES[kind])}
                  className="rounded-full border border-gray-200 px-3 py-1 text-xs text-gray-600
                             hover:border-blue-300 hover:text-blue-700 hover:bg-blue-50 transition-colors"
                >
                  {kind}
                </button>
              ))}
            </div>
          </div>
        )}

        <textarea
          value={yaml}
          onChange={(e) => setYaml(e.target.value)}
          spellCheck={false}
          placeholder="Pick a template above…"
          className="w-full h-[28rem] bg-gray-950 text-gray-100 font-mono text-xs leading-5 p-4
                     resize-none focus:outline-none"
        />

        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-200 bg-gray-50">
          <button
            onClick={() => navigate(-1)}
            className="rounded-lg border border-gray-300 px-3.5 py-2 text-sm text-gray-700 hover:bg-gray-50 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => setConfirmOpen(true)}
            disabled={!yaml.trim()}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white
                       hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Create
          </button>
        </div>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Create cluster-scoped resource"
        message={
          <>
            This will create <span className="font-mono font-medium text-gray-800">{preview.kind}/{preview.name}</span>,
            cluster-wide (no namespace). The manifest is validated by the cluster before anything is persisted.
          </>
        }
        confirmLabel="Create"
        onConfirm={create}
        onClose={() => setConfirmOpen(false)}
      />
    </Layout>
  )
}
