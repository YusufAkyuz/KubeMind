import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Layout } from '../components/Layout'
import { IconChevronRight } from '../components/Icons'
import { SessionWarningToast } from '../components/SessionWarningToast'
import { useWsTerminal } from '../hooks/useWsTerminal'

export function NodeExecPage() {
  const { clusterId, nodeName } = useParams<{ clusterId: string; nodeName: string }>()
  const [confirmed, setConfirmed] = useState(false)
  const [dismissed, setDismissed] = useState(false)

  const wsUrl = clusterId && nodeName
    ? `/ws/exec-node?clusterId=${clusterId}&node=${encodeURIComponent(nodeName)}`
    : null

  const { termElRef, connState } = useWsTerminal(wsUrl, confirmed)

  return (
    <Layout fullBleed>
      <div className="h-full w-full flex flex-col bg-gray-950">
        {/* Toolbar */}
        <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
          <nav className="flex items-center gap-1.5 text-xs text-gray-500 min-w-0">
            <Link to={`/clusters/${clusterId}/nodes`} className="hover:text-gray-300 transition-colors truncate">
              Nodes
            </Link>
            <IconChevronRight className="w-3 h-3 shrink-0" />
            <span className="text-gray-300 truncate">{nodeName}</span>
            <IconChevronRight className="w-3 h-3 shrink-0" />
            <span className="text-gray-400">shell</span>
          </nav>

          {confirmed && (
            <div className="ml-auto flex items-center gap-2 shrink-0">
              <span className={`w-1.5 h-1.5 rounded-full ${
                connState === 'connected' ? 'bg-emerald-400'
                : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
              }`} />
              <span className="text-[10px] text-gray-500 uppercase tracking-wide">
                {connState === 'connected' ? 'Connected'
                  : connState === 'connecting' ? 'Provisioning debug pod…' : 'Closed'}
              </span>
            </div>
          )}
        </div>

        {/* Terminal / connect prompt fills all remaining space */}
        {confirmed ? (
          <div ref={termElRef} className="flex-1 min-h-0 overflow-hidden p-2" />
        ) : (
          <div className="flex-1 min-h-0 flex items-center justify-center">
            <button
              onClick={() => setConfirmed(true)}
              className="rounded-md bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-700 transition-colors"
            >
              Connect to {nodeName}
            </button>
          </div>
        )}
      </div>

      {!confirmed && !dismissed && (
        <SessionWarningToast
          tone="amber"
          message={`Privileged shell with full host access to ${nodeName}.`}
          onConnect={() => setConfirmed(true)}
          onDismiss={() => setDismissed(true)}
        />
      )}
    </Layout>
  )
}
