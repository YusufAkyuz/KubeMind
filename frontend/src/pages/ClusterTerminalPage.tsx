import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { Layout } from '../components/Layout'
import { SessionWarningToast } from '../components/SessionWarningToast'
import { useWsTerminal } from '../hooks/useWsTerminal'

export function ClusterTerminalPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const [confirmed, setConfirmed] = useState(false)
  const [dismissed, setDismissed] = useState(false)

  const wsUrl = clusterId ? `/ws/exec-cluster?clusterId=${clusterId}` : null
  const { termElRef, connState } = useWsTerminal(wsUrl, confirmed)

  return (
    <Layout fullBleed>
      <div className="h-full w-full flex flex-col bg-gray-950">
        {/* Toolbar */}
        <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
          <span className="text-xs text-gray-300 font-medium">Cluster Terminal</span>
          <span className="text-xs text-gray-500 font-mono">kubectl</span>

          {confirmed && (
            <div className="ml-auto flex items-center gap-2 shrink-0">
              <span className={`w-1.5 h-1.5 rounded-full ${
                connState === 'connected' ? 'bg-emerald-400'
                : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
              }`} />
              <span className="text-[10px] text-gray-500 uppercase tracking-wide">
                {connState === 'connected' ? 'Connected'
                  : connState === 'connecting' ? 'Provisioning…' : 'Closed'}
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
              className="rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 transition-colors"
            >
              Connect
            </button>
          </div>
        )}
      </div>

      {!confirmed && !dismissed && (
        <SessionWarningToast
          tone="red"
          message="Grants cluster-admin for this session — commands aren't individually audited."
          onConnect={() => setConfirmed(true)}
          onDismiss={() => setDismissed(true)}
        />
      )}
    </Layout>
  )
}
