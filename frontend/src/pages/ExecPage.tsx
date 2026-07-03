import { useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { IconChevronRight } from '../components/Icons'
import type { Pod } from '../types/k8s'

type ConnState = 'connecting' | 'connected' | 'closed'

export function ExecPage() {
  const { clusterId, ns, pod } = useParams<{ clusterId: string; ns: string; pod: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const container = searchParams.get('container') ?? ''

  const [connState, setConnState] = useState<ConnState>('connecting')
  const termElRef = useRef<HTMLDivElement>(null)
  const termRef = useRef<Terminal | null>(null)
  const fitRef = useRef<FitAddon | null>(null)
  const wsRef = useRef<WebSocket | null>(null)

  const { data: podData } = useQuery<Pod>({
    queryKey: ['pod', clusterId, ns, pod],
    queryFn: async () => (await api.get<Pod>(`/clusters/${clusterId}/namespaces/${ns}/pods/${pod}`)).data,
    enabled: !!clusterId && !!ns && !!pod,
    staleTime: 60_000,
  })

  const containerName = container || podData?.containers[0]?.name || ''

  useEffect(() => {
    if (!termElRef.current || !clusterId || !ns || !pod || !containerName) return

    const term = new Terminal({
      cursorBlink: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 13,
      theme: { background: '#030712', foreground: '#e5e7eb' },
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(termElRef.current)
    fit.fit()
    termRef.current = term
    fitRef.current = fit

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${window.location.host}/ws/exec?clusterId=${clusterId}`
      + `&ns=${encodeURIComponent(ns)}&pod=${encodeURIComponent(pod)}`
      + `&container=${encodeURIComponent(containerName)}`
    const ws = new WebSocket(url)
    wsRef.current = ws

    ws.onopen = () => {
      setConnState('connected')
      term.focus()
      sendResize()
    }
    ws.onmessage = (e) => term.write(e.data as string)
    ws.onclose = () => {
      setConnState('closed')
      term.write('\r\n\x1b[90m[session closed]\x1b[0m\r\n')
    }

    const sendResize = () => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }))
      }
    }

    const dataDisposable = term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'stdin', data }))
      }
    })

    const resizeDisposable = term.onResize(() => sendResize())
    const onWindowResize = () => fit.fit()
    window.addEventListener('resize', onWindowResize)

    return () => {
      window.removeEventListener('resize', onWindowResize)
      dataDisposable.dispose()
      resizeDisposable.dispose()
      ws.close()
      term.dispose()
    }
  }, [clusterId, ns, pod, containerName])

  const handleContainerChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSearchParams({ container: e.target.value })
  }

  return (
    <Layout>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 mb-4 flex-wrap">
        <Link to={`/clusters/${clusterId}/namespaces/${ns}/pods`} className="hover:text-gray-700 transition-colors">
          {ns}
        </Link>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600">{pod}</span>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-gray-600 font-medium">Terminal</span>
      </nav>

      <div className="rounded-xl border border-gray-200 bg-gray-950 overflow-hidden shadow-lg flex flex-col"
           style={{ height: 'calc(100vh - 11rem)' }}>
        {/* Toolbar */}
        <div className="flex items-center gap-3 px-4 py-2.5 border-b border-gray-800 bg-gray-900 shrink-0">
          {podData && podData.containers.length > 1 ? (
            <select
              value={containerName}
              onChange={handleContainerChange}
              className="h-7 rounded-md border border-gray-700 bg-gray-800 px-2 text-xs text-gray-200
                         focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {podData.containers.map((c) => (
                <option key={c.name} value={c.name}>{c.name}</option>
              ))}
            </select>
          ) : (
            <span className="text-xs text-gray-400 font-mono">{containerName}</span>
          )}
          <div className="ml-auto flex items-center gap-2">
            <span className={`w-1.5 h-1.5 rounded-full ${
              connState === 'connected' ? 'bg-emerald-400'
              : connState === 'connecting' ? 'bg-amber-400 animate-pulse' : 'bg-gray-500'
            }`} />
            <span className="text-[10px] text-gray-500 uppercase tracking-wide">
              {connState === 'connected' ? 'Connected'
                : connState === 'connecting' ? 'Connecting…' : 'Closed'}
            </span>
          </div>
        </div>

        {/* Terminal surface */}
        <div ref={termElRef} className="flex-1 overflow-hidden p-2" />
      </div>

      <p className="mt-2 text-xs text-gray-400">
        Interactive shell (/bin/sh). This session is ADMIN-only and recorded in the audit log.
      </p>
    </Layout>
  )
}
