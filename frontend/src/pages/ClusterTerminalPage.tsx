import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { Layout } from '../components/Layout'
import { useWsTerminal } from '../hooks/useWsTerminal'

export function ClusterTerminalPage() {
  const { clusterId } = useParams<{ clusterId: string }>()
  const [confirmed, setConfirmed] = useState(false)

  const wsUrl = clusterId ? `/ws/exec-cluster?clusterId=${clusterId}` : null
  const { termElRef, connState } = useWsTerminal(wsUrl, confirmed)

  return (
    <Layout>
      <h1 className="text-xl font-semibold tracking-tight text-gray-900 mb-4">Cluster Terminal</h1>

      {!confirmed ? (
        <div className="max-w-xl rounded-xl border border-red-200 bg-red-50 p-6">
          <h2 className="text-base font-semibold text-red-900">This grants cluster-admin for the session</h2>
          <p className="mt-2 text-sm text-red-800 leading-relaxed">
            Connecting opens a real <span className="font-mono">kubectl</span> shell running as a temporary
            service account bound to <span className="font-medium">cluster-admin</span> — full read/write
            access to everything in this cluster, with no per-command allowlisting.
          </p>
          <ul className="mt-3 text-sm text-red-800 list-disc list-inside space-y-1">
            <li>
              Unlike every other action in KubeMind, commands you run here are <span className="font-medium">not</span> individually
              audited — only the fact that this session was opened is logged.
            </li>
            <li>The service account, its cluster-admin binding, and the pod are all deleted when you close this session.</li>
            <li>Use this when you need something KubeMind doesn't have a dedicated screen for yet.</li>
          </ul>
          <button
            onClick={() => setConfirmed(true)}
            className="mt-4 rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white
                       hover:bg-red-700 transition-colors"
          >
            I understand — connect
          </button>
        </div>
      ) : (
        <>
          <div className="rounded-xl border border-gray-200 bg-gray-950 overflow-hidden shadow-lg flex flex-col"
               style={{ height: 'calc(100vh - 11rem)' }}>
            <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
              <span className="text-xs text-gray-400 font-mono">kubectl</span>
              <div className="ml-auto flex items-center gap-2">
                <span className={`w-1.5 h-1.5 rounded-full ${
                  connState === 'connected' ? 'bg-emerald-400'
                  : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
                }`} />
                <span className="text-[10px] text-gray-500 uppercase tracking-wide">
                  {connState === 'connected' ? 'Connected'
                    : connState === 'connecting' ? 'Provisioning…' : 'Closed'}
                </span>
              </div>
            </div>
            <div ref={termElRef} className="flex-1 overflow-hidden p-2" />
          </div>
          <p className="mt-2 text-xs text-gray-400">
            cluster-admin shell. ADMIN-only; only session open/close is audited, not individual commands.
          </p>
        </>
      )}
    </Layout>
  )
}
