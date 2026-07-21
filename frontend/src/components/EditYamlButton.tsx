import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { Modal } from './Modal'
import { useToast } from './Toast'
import { streamText } from '../utils/streamFetch'
import { stripLeadingFence, stripTrailingFence } from '../utils/yamlFence'
import { IconSparkles } from './Icons'
import { DiffViewer, useDiffCount } from './DiffViewer'

interface Props {
  clusterId: string
  /** Omit for cluster-scoped kinds (e.g. Namespace) — no namespace segment in the route. */
  ns?: string
  kind: string
  name: string
  onApplied?: () => void
}

/** Self-contained "Edit YAML" button + modal, generic across every editable resource kind. */
export function EditYamlButton({ clusterId, ns, kind, name, onApplied }: Props) {
  const [open, setOpen] = useState(false)
  const [originalYaml, setOriginalYaml] = useState('')
  const [yaml, setYaml] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const [showDiff, setShowDiff] = useState(false)

  const [aiInstruction, setAiInstruction] = useState('')
  const [aiEditing, setAiEditing] = useState(false)
  const [aiError, setAiError] = useState<string | null>(null)

  const toast = useToast()
  const changeCount = useDiffCount(originalYaml, yaml)

  const base = ns
    ? `/clusters/${clusterId}/namespaces/${ns}/resources/${kind}/${name}/yaml`
    : `/clusters/${clusterId}/resources/${kind}/${name}/yaml`

  const openEditor = async () => {
    setOpen(true)
    setLoading(true)
    setError(null)
    setAiError(null)
    setAiInstruction('')
    setShowDiff(false)
    try {
      const res = await api.get<string>(base, {
        responseType: 'text',
        transformResponse: [(data) => data], // keep raw YAML string, skip JSON parse
      })
      setOriginalYaml(res.data)
      setYaml(res.data)
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not load YAML'))
    } finally {
      setLoading(false)
    }
  }

  const apply = async () => {
    setApplying(true)
    setError(null)
    try {
      await api.put(base, yaml, { headers: { 'Content-Type': 'application/yaml' } })
      toast.success(`Applied YAML for ${name}`)
      setOpen(false)
      onApplied?.()
    } catch (e) {
      setError(apiErrorMessage(e, 'Apply failed'))
    } finally {
      setApplying(false)
    }
  }

  const editWithAi = async () => {
    if (!aiInstruction.trim() || aiEditing) return
    setAiEditing(true)
    setAiError(null)
    setShowDiff(false)
    let buffer = ''
    try {
      await streamText(
        `/api/clusters/${clusterId}/resources/ai-edit`,
        { yaml, instruction: aiInstruction.trim() },
        (chunk) => {
          buffer += chunk
          setYaml(stripLeadingFence(buffer))
        },
      )
      setYaml((prev) => stripTrailingFence(prev))
    } catch (e) {
      setAiError(e instanceof Error ? e.message : 'AI edit failed')
    } finally {
      setAiEditing(false)
    }
  }

  return (
    <>
      <button
        onClick={openEditor}
        className="rounded-md border border-gray-300 dark:border-neutral-600 px-3 py-1.5 text-xs font-medium text-gray-700 dark:text-neutral-300
                   hover:bg-gray-50 dark:hover:bg-neutral-800 transition-colors"
      >
        Edit YAML
      </button>

      <Modal open={open} title={`Edit YAML — ${name}`} onClose={() => setOpen(false)} wide>
        <div className="space-y-3">
          {loading && <p className="text-sm text-gray-400 dark:text-neutral-500">Loading manifest…</p>}

          {!loading && (
            <>
              {/* AI edit bar */}
              <div className="flex items-center gap-2 rounded-lg bg-gradient-to-r from-blue-50/60 to-violet-50/60 dark:from-blue-500/10 dark:to-violet-500/10 border border-gray-200 dark:border-neutral-700 px-3 py-2">
                <input
                  type="text"
                  value={aiInstruction}
                  onChange={(e) => setAiInstruction(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') editWithAi() }}
                  placeholder='Describe a change, e.g. "increase replicas to 5"'
                  disabled={aiEditing || applying || showDiff}
                  className="flex-1 rounded-md border border-gray-300 dark:border-neutral-600 bg-white dark:bg-neutral-800
                             text-gray-900 dark:text-neutral-100 px-2.5 py-1.5 text-xs
                             focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent
                             disabled:opacity-60"
                />
                <button
                  onClick={editWithAi}
                  disabled={aiEditing || applying || !aiInstruction.trim() || showDiff}
                  className="shrink-0 inline-flex items-center gap-1.5 rounded-md bg-gradient-to-br from-blue-600 to-violet-600
                             px-2.5 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50
                             disabled:cursor-not-allowed transition-opacity"
                >
                  <IconSparkles className="w-3.5 h-3.5" />
                  {aiEditing ? 'Editing…' : 'Edit with AI'}
                </button>
              </div>
              {aiError && <p className="text-xs text-red-600 dark:text-red-400">{aiError}</p>}

              {/* Editor / Diff toggle */}
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setShowDiff(false)}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                    !showDiff
                      ? 'bg-neutral-900 dark:bg-neutral-100 text-white dark:text-neutral-900'
                      : 'text-neutral-500 dark:text-neutral-400 hover:text-gray-700 dark:hover:text-neutral-200 hover:bg-gray-100 dark:hover:bg-neutral-800'
                  }`}
                >
                  Editor
                </button>
                <button
                  onClick={() => setShowDiff(true)}
                  disabled={changeCount === 0}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors
                    disabled:opacity-40 disabled:cursor-not-allowed ${
                    showDiff
                      ? 'bg-neutral-900 dark:bg-neutral-100 text-white dark:text-neutral-900'
                      : 'text-neutral-500 dark:text-neutral-400 hover:text-gray-700 dark:hover:text-neutral-200 hover:bg-gray-100 dark:hover:bg-neutral-800'
                  }`}
                >
                  Review changes
                  {changeCount > 0 && (
                    <span className={`ml-1.5 inline-flex items-center justify-center px-1.5 py-0.5 rounded-full text-[10px] font-semibold ${
                      showDiff ? 'bg-blue-500 text-white' : 'bg-blue-100 dark:bg-blue-500/20 text-blue-700 dark:text-blue-400'
                    }`}>
                      {changeCount}
                    </span>
                  )}
                </button>
              </div>

              {/* Editor or Diff view */}
              {showDiff ? (
                <DiffViewer oldText={originalYaml} newText={yaml} />
              ) : (
                <textarea
                  value={yaml}
                  onChange={(e) => setYaml(e.target.value)}
                  spellCheck={false}
                  className="w-full h-[45vh] rounded-md border border-neutral-800 bg-neutral-950 text-neutral-100
                             font-mono text-xs leading-5 p-3 resize-none
                             focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                />
              )}
            </>
          )}
          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
          <div className="flex items-center justify-between">
            <p className="text-xs text-gray-400 dark:text-neutral-500">
              kind / name{ns ? ' / namespace' : ''} cannot be changed; conflicts return an error — reload and retry.
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setOpen(false)}
                disabled={applying}
                className="rounded-md border border-gray-300 dark:border-neutral-600 px-3.5 py-2 text-sm text-gray-700 dark:text-neutral-300
                           hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={apply}
                disabled={applying || loading || aiEditing || !yaml.trim() || changeCount === 0}
                className="rounded-md bg-blue-600 px-3.5 py-2 text-sm font-medium text-white
                           hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                {applying ? 'Applying…' : 'Apply'}
              </button>
            </div>
          </div>
        </div>
      </Modal>
    </>
  )
}
