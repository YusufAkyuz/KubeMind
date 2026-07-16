import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, apiErrorMessage } from '../api/client'
import { Layout } from '../components/Layout'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { useToast } from '../components/Toast'
import { streamText } from '../utils/streamFetch'
import { renderLiteMarkdown } from '../utils/markdownLite'
import { ALLOWED_KINDS, KIND_ROUTES, KIND_TEMPLATES, type AllowedKind } from '../utils/resourceKinds'
import { stripLeadingFence, stripTrailingFence } from '../utils/yamlFence'
import { IconSparkles, IconChevronRight } from '../components/Icons'

/** Loose client-side preview only — the server validates authoritatively. */
function extractPreview(yaml: string): { kind: string; name: string } {
  const kind = yaml.match(/^kind:\s*(\S+)/m)?.[1] ?? 'resource'
  const name = yaml.match(/^\s*name:\s*(\S+)/m)?.[1] ?? 'unnamed'
  return { kind, name }
}


export function CreateResourcePage() {
  const { clusterId, ns } = useParams<{ clusterId: string; ns: string }>()
  const [searchParams] = useSearchParams()
  const kindHint = searchParams.get('kind')
  const navigate = useNavigate()
  const toast = useToast()

  const [yaml, setYaml] = useState('')
  const [prompt, setPrompt] = useState('')
  const [drafting, setDrafting] = useState(false)
  const [draftError, setDraftError] = useState<string | null>(null)

  const [analysis, setAnalysis] = useState('')
  const [analyzing, setAnalyzing] = useState(false)
  const [analyzeError, setAnalyzeError] = useState<string | null>(null)
  const hasAnalyzed = useRef(false)

  const [confirmOpen, setConfirmOpen] = useState(false)

  const busy = drafting || analyzing

  // Prefill a starter template from the ?kind= hint (from a "+ Create" button elsewhere).
  useEffect(() => {
    if (yaml || !ns || ns === '_') return
    if (kindHint && (ALLOWED_KINDS as readonly string[]).includes(kindHint)) {
      setYaml(KIND_TEMPLATES[kindHint as AllowedKind](ns))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kindHint, ns])

  const loadTemplate = (kind: AllowedKind) => {
    if (!ns) return
    setYaml(KIND_TEMPLATES[kind](ns))
    setAnalysis('')
    hasAnalyzed.current = false
  }

  const draft = async () => {
    if (!prompt.trim() || busy || !clusterId || !ns) return
    setDrafting(true)
    setDraftError(null)
    setYaml('')
    setAnalysis('')
    hasAnalyzed.current = false
    let buffer = ''
    try {
      await streamText(
        `/api/clusters/${clusterId}/namespaces/${ns}/resources/draft`,
        { prompt: prompt.trim() },
        (chunk) => {
          buffer += chunk
          setYaml(stripLeadingFence(buffer))
        },
      )
      setYaml((prev) => stripTrailingFence(prev))
    } catch (e) {
      setDraftError(e instanceof Error ? e.message : 'Draft failed')
    } finally {
      setDrafting(false)
    }
  }

  const analyze = async () => {
    if (!yaml.trim() || busy || !clusterId || !ns) return
    setAnalyzing(true)
    setAnalyzeError(null)
    setAnalysis('')
    hasAnalyzed.current = true
    try {
      await streamText(
        `/api/clusters/${clusterId}/namespaces/${ns}/resources/analyze`,
        { yaml },
        (chunk) => setAnalysis((prev) => prev + chunk),
      )
    } catch (e) {
      setAnalyzeError(e instanceof Error ? e.message : 'Analysis failed')
    } finally {
      setAnalyzing(false)
    }
  }

  const create = async () => {
    if (!clusterId || !ns) return
    try {
      const res = await api.post<{ kind: string; name: string; namespace: string }>(
        `/clusters/${clusterId}/namespaces/${ns}/resources`,
        { yaml },
      )
      toast.success(`${res.data.kind} "${res.data.name}" created`)
      const route = KIND_ROUTES[res.data.kind]
      navigate(route ? `/clusters/${clusterId}/namespaces/${ns}/${route}` : `/clusters/${clusterId}/namespaces/${ns}/pods`)
    } catch (e) {
      throw new Error(apiErrorMessage(e, 'Create failed'))
    }
  }

  const preview = extractPreview(yaml)

  return (
    <Layout>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 dark:text-slate-500 mb-4 flex-wrap">
        <span>{ns}</span>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600 dark:text-slate-400 font-medium">Create resource</span>
      </nav>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_380px] gap-4 items-start">
        {/* ── Editor column ─────────────────────────────────────────────── */}
        <div className="rounded-xl border border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm overflow-hidden">
          {/* Kind quick-start (only while the editor is empty) */}
          {!yaml && (
            <div className="px-4 py-3 border-b border-gray-200 dark:border-slate-700">
              <p className="text-xs font-medium text-gray-500 dark:text-slate-400 mb-2">Start from a template</p>
              <div className="flex flex-wrap gap-1.5">
                {ALLOWED_KINDS.map((kind) => (
                  <button
                    key={kind}
                    onClick={() => loadTemplate(kind)}
                    className="rounded-full border border-gray-200 dark:border-slate-700 px-3 py-1 text-xs text-gray-600 dark:text-slate-400
                               hover:border-blue-300 hover:text-blue-700 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-500/10 transition-colors"
                  >
                    {kind}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* AI draft prompt */}
          <div className="px-4 py-3 border-b border-gray-200 dark:border-slate-700 bg-gradient-to-r from-blue-50/60 to-violet-50/60 dark:from-blue-500/10 dark:to-violet-500/10">
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') draft() }}
                placeholder='Describe what you want, e.g. "a deployment running nginx with 3 replicas"'
                disabled={busy}
                className="flex-1 rounded-lg border border-gray-300 dark:border-slate-600 bg-white dark:bg-slate-800
                           text-gray-900 dark:text-slate-100 px-3 py-2 text-sm
                           focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                           disabled:opacity-60"
              />
              <button
                onClick={draft}
                disabled={busy || !prompt.trim()}
                className="shrink-0 inline-flex items-center gap-1.5 rounded-lg bg-gradient-to-br from-blue-600 to-violet-600
                           px-3.5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50
                           disabled:cursor-not-allowed transition-opacity"
              >
                <IconSparkles className="w-4 h-4" />
                {drafting ? 'Drafting…' : 'Draft with AI'}
              </button>
            </div>
            {draftError && <p className="mt-1.5 text-xs text-red-600 dark:text-red-400">{draftError}</p>}
          </div>

          {/* YAML editor */}
          <textarea
            value={yaml}
            onChange={(e) => { setYaml(e.target.value); setAnalysis(''); hasAnalyzed.current = false }}
            spellCheck={false}
            placeholder="Pick a template above, or draft one with AI…"
            className="w-full h-[28rem] bg-gray-950 text-gray-100 font-mono text-xs leading-5 p-4
                       resize-none focus:outline-none"
          />

          {/* Action bar */}
          <div className="flex items-center justify-between px-4 py-3 border-t border-gray-200 dark:border-slate-700 bg-gray-50 dark:bg-slate-800/60">
            <button
              onClick={analyze}
              disabled={busy || !yaml.trim()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-gray-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-3.5 py-2
                         text-sm font-medium text-gray-700 dark:text-slate-300 hover:bg-gray-50 dark:hover:bg-slate-700 disabled:opacity-50
                         disabled:cursor-not-allowed transition-colors"
            >
              <IconSparkles className="w-4 h-4 text-blue-600 dark:text-blue-400" />
              {analyzing ? 'Analyzing…' : 'Analyze with AI'}
            </button>
            <div className="flex items-center gap-2">
              <button
                onClick={() => navigate(-1)}
                className="rounded-lg border border-gray-300 dark:border-slate-600 px-3.5 py-2 text-sm text-gray-700 dark:text-slate-300 hover:bg-gray-50 dark:hover:bg-slate-800 transition-colors"
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
        </div>

        {/* ── AI assistant column ───────────────────────────────────────── */}
        <div className="rounded-xl border border-gray-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-sm overflow-hidden lg:sticky lg:top-6">
          <div className="px-4 py-3 border-b border-gray-200 dark:border-slate-700 bg-gray-50 dark:bg-slate-800/60">
            <p className="text-sm font-medium text-gray-900 dark:text-slate-100">AI Review</p>
            <p className="text-xs text-gray-400 dark:text-slate-500 mt-0.5">Correctness, security, and best-practice notes</p>
          </div>
          <div className="px-4 py-3 max-h-[32rem] overflow-y-auto">
            {!hasAnalyzed.current && !analyzing && (
              <p className="text-sm text-gray-400 dark:text-slate-500">
                Click "Analyze with AI" to review the manifest before creating it.
              </p>
            )}
            {analyzing && !analysis && (
              <div className="space-y-2 animate-pulse py-1">
                <div className="h-3 bg-gray-100 dark:bg-slate-700 rounded w-4/5" />
                <div className="h-3 bg-gray-100 dark:bg-slate-700 rounded w-full" />
                <div className="h-3 bg-gray-100 dark:bg-slate-700 rounded w-3/5" />
              </div>
            )}
            {analyzeError && <p className="text-sm text-red-600 dark:text-red-400">{analyzeError}</p>}
            {analysis && (
              <>
                <div className="space-y-2">{renderLiteMarkdown(analysis)}</div>
                <p className="mt-3 pt-3 border-t border-gray-100 dark:border-slate-800 text-xs text-gray-400 dark:text-slate-500">
                  AI-generated — verify before acting.
                </p>
              </>
            )}
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Create resource"
        message={
          <>
            This will create <span className="font-mono font-medium text-gray-800 dark:text-slate-200">{preview.kind}/{preview.name}</span> in
            namespace <span className="font-medium">{ns}</span>. The manifest is validated by the cluster before
            anything is persisted.
          </>
        }
        confirmLabel="Create"
        onConfirm={create}
        onClose={() => setConfirmOpen(false)}
      />
    </Layout>
  )
}
