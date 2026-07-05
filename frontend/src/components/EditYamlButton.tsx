import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { Modal } from './Modal'
import { useToast } from './Toast'

interface Props {
  clusterId: string
  ns: string
  kind: string
  name: string
  onApplied?: () => void
}

/** Self-contained "Edit YAML" button + modal, generic across every editable resource kind. */
export function EditYamlButton({ clusterId, ns, kind, name, onApplied }: Props) {
  const [open, setOpen] = useState(false)
  const [yaml, setYaml] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [applying, setApplying] = useState(false)
  const toast = useToast()

  const base = `/clusters/${clusterId}/namespaces/${ns}/resources/${kind}/${name}/yaml`

  const openEditor = async () => {
    setOpen(true)
    setLoading(true)
    setError(null)
    try {
      const res = await api.get<string>(base, {
        responseType: 'text',
        transformResponse: [(data) => data], // keep raw YAML string, skip JSON parse
      })
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

  return (
    <>
      <button
        onClick={openEditor}
        className="rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700
                   hover:bg-gray-50 transition-colors"
      >
        Edit YAML
      </button>

      <Modal open={open} title={`Edit YAML — ${name}`} onClose={() => setOpen(false)} wide>
        <div className="space-y-3">
          {loading && <p className="text-sm text-gray-400">Loading manifest…</p>}
          {!loading && (
            <textarea
              value={yaml}
              onChange={(e) => setYaml(e.target.value)}
              spellCheck={false}
              className="w-full h-[50vh] rounded-md border border-gray-300 bg-gray-950 text-gray-100
                         font-mono text-xs leading-5 p-3 resize-none
                         focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
          )}
          {error && <p className="text-sm text-red-600">{error}</p>}
          <div className="flex items-center justify-between">
            <p className="text-xs text-gray-400">
              kind / name / namespace cannot be changed; conflicts return an error — reload and retry.
            </p>
            <div className="flex gap-2">
              <button
                onClick={() => setOpen(false)}
                disabled={applying}
                className="rounded-md border border-gray-300 px-3.5 py-2 text-sm text-gray-700
                           hover:bg-gray-50 disabled:opacity-50 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={apply}
                disabled={applying || loading || !yaml.trim()}
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
