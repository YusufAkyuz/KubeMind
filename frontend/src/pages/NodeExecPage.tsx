import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Layout } from '../components/Layout'
import { IconChevronRight } from '../components/Icons'
import { useWsTerminal } from '../hooks/useWsTerminal'

export function NodeExecPage() {
  const { clusterId, nodeName } = useParams<{ clusterId: string; nodeName: string }>()
  const [confirmed, setConfirmed] = useState(false)

  const wsUrl = clusterId && nodeName
    ? `/ws/exec-node?clusterId=${clusterId}&node=${encodeURIComponent(nodeName)}`
    : null

  const { termElRef, connState } = useWsTerminal(wsUrl, confirmed)

  return (
    <Layout>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 mb-4 flex-wrap">
        <Link to={`/clusters/${clusterId}/nodes`} className="hover:text-gray-700 transition-colors">
          Nodes
        </Link>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600">{nodeName}</span>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600 font-medium">Node shell</span>
      </nav>

      {!confirmed ? (
        <div className="max-w-xl rounded-xl border border-amber-200 bg-amber-50 p-6">
          <h1 className="text-base font-semibold text-amber-900">This opens a privileged host shell</h1>
          <p className="mt-2 text-sm text-amber-800 leading-relaxed">
            Connecting schedules a temporary, <span className="font-medium">privileged</span> pod directly
            onto node <span className="font-mono">{nodeName}</span>, with the node's entire root filesystem
            and process namespace mounted in. Anything you do here has the same access as a root shell on
            the physical/virtual machine itself — not just this cluster.
          </p>
          <ul className="mt-3 text-sm text-amber-800 list-disc list-inside space-y-1">
            <li>The debug pod is deleted automatically when you close this session.</li>
            <li>Opening this session is recorded in the audit log.</li>
          </ul>
          <button
            onClick={() => setConfirmed(true)}
            className="mt-4 rounded-md bg-amber-600 px-4 py-2 text-sm font-medium text-white
                       hover:bg-amber-700 transition-colors"
          >
            I understand — connect
          </button>
        </div>
      ) : (
        <>
          <div className="rounded-xl border border-gray-200 bg-gray-950 overflow-hidden shadow-lg flex flex-col"
               style={{ height: 'calc(100vh - 11rem)' }}>
            <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
              <span className="text-xs text-gray-400 font-mono">{nodeName}</span>
              <div className="ml-auto flex items-center gap-2">
                <span className={`w-1.5 h-1.5 rounded-full ${
                  connState === 'connected' ? 'bg-emerald-400'
                  : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
                }`} />
                <span className="text-[10px] text-gray-500 uppercase tracking-wide">
                  {connState === 'connected' ? 'Connected'
                    : connState === 'connecting' ? 'Provisioning debug pod…' : 'Closed'}
                </span>
              </div>
            </div>
            <div ref={termElRef} className="flex-1 overflow-hidden p-2" />
          </div>
          <p className="mt-2 text-xs text-gray-400">
            Root shell on {nodeName} via a temporary privileged pod. ADMIN-only, session recorded in the audit log.
          </p>
        </>
      )}
    </Layout>
  )
}
