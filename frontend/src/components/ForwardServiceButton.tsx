import { useState } from 'react'
import { api, apiErrorMessage } from '../api/client'
import { useToast } from './Toast'
import { IconTerminal } from './Icons'

interface Props {
  clusterId: string
  ns: string
  name: string
}

interface OpenedSession {
  sessionId: string
  proxyPath: string
}

/**
 * Opens an HTTP tunnel to a Service's backing pod (see PortForwardController)
 * and opens it in a new tab. ADMIN-only, same trust tier as the Cluster
 * Terminal — every session open/close is audited backend-side. Idle sessions
 * are swept automatically; nothing to clean up client-side.
 */
export function ForwardServiceButton({ clusterId, ns, name }: Props) {
  const [loading, setLoading] = useState(false)
  const toast = useToast()

  const openTunnel = async () => {
    setLoading(true)
    try {
      const res = await api.post<OpenedSession>(`/clusters/${clusterId}/namespaces/${ns}/services/${name}/forward`)
      window.open(res.data.proxyPath, '_blank', 'noopener,noreferrer')
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Could not forward this service'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <button
      onClick={openTunnel}
      disabled={loading}
      title="Open a tunnel to this service's backing pod in a new tab"
      className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 dark:border-neutral-600 px-3 py-1.5 text-xs font-medium
                 text-gray-700 dark:text-neutral-300 hover:bg-gray-50 dark:hover:bg-neutral-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
    >
      <IconTerminal className="w-3.5 h-3.5" />
      {loading ? 'Opening…' : 'Forward'}
    </button>
  )
}
