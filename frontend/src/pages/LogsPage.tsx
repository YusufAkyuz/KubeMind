import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import { Layout } from '../components/Layout'
import { IconArrowDown, IconChevronRight, IconRefresh, IconSearch, IconX } from '../components/Icons'
import type { Pod } from '../types/k8s'

const MAX_LINES = 5_000
const RECONNECT_DELAY_MS = 2_000
const TAIL_LINES = 200

interface LogLine {
  id: number
  text: string
}

type ConnState = 'connecting' | 'connected' | 'paused' | 'error' | 'closed'

let lineIdSeq = 0

export function LogsPage() {
  const { clusterId, ns, pod } = useParams<{ clusterId: string; ns: string; pod: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const container = searchParams.get('container') ?? ''

  const [lines, setLines] = useState<LogLine[]>([])
  const [follow, setFollow] = useState(true)
  const [search, setSearch] = useState('')
  const [connState, setConnState] = useState<ConnState>('connecting')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const scrollRef = useRef<HTMLDivElement>(null)
  const esRef = useRef<EventSource | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const cancelledRef = useRef(false)

  // Fetch pod to get container list
  const { data: podData } = useQuery<Pod>({
    queryKey: ['pod', clusterId, ns, pod],
    queryFn: async () => (await api.get<Pod>(`/clusters/${clusterId}/namespaces/${ns}/pods/${pod}`)).data,
    enabled: !!clusterId && !!ns && !!pod,
    staleTime: 60_000,
  })

  const containerName = container || podData?.containers[0]?.name || ''

  const connect = useCallback(() => {
    if (!clusterId || !ns || !pod || !containerName || cancelledRef.current) return

    esRef.current?.close()

    const url = `/api/clusters/${clusterId}/namespaces/${ns}/pods/${pod}/logs/stream?container=${encodeURIComponent(containerName)}&tailLines=${TAIL_LINES}`
    const es = new EventSource(url, { withCredentials: true })
    esRef.current = es
    setConnState('connecting')
    setErrorMsg(null)

    es.addEventListener('log', (e) => {
      setConnState('connected')
      setLines((prev) => {
        const next = [...prev, { id: ++lineIdSeq, text: e.data as string }]
        return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next
      })
    })

    es.addEventListener('error', (e) => {
      const msg = (e as MessageEvent).data as string | undefined
      setErrorMsg(msg ?? 'Stream error')
      setConnState('error')
      es.close()
    })

    es.onerror = () => {
      if (cancelledRef.current) return
      setConnState('error')
      es.close()
      reconnectTimer.current = setTimeout(() => {
        if (!cancelledRef.current) connect()
      }, RECONNECT_DELAY_MS)
    }
  }, [clusterId, ns, pod, containerName])

  // (Re)connect when container changes
  useEffect(() => {
    cancelledRef.current = false
    if (containerName) connect()
    return () => {
      cancelledRef.current = true
      esRef.current?.close()
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current)
    }
  }, [connect, containerName])

  // Auto-scroll when following
  useEffect(() => {
    if (follow && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [lines, follow])

  // Pause follow on manual scroll up
  const handleScroll = () => {
    const el = scrollRef.current
    if (!el) return
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
    if (!atBottom && follow) setFollow(false)
  }

  const scrollToBottom = () => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    setFollow(true)
  }

  const handleContainerChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSearchParams({ container: e.target.value })
    setLines([])
  }

  const clearLogs = () => setLines([])

  const reconnect = () => {
    setLines([])
    connect()
  }

  // Filter lines for search
  const filtered = search
    ? lines.filter((l) => l.text.toLowerCase().includes(search.toLowerCase()))
    : lines

  const searchLower = search.toLowerCase()

  function highlight(text: string) {
    if (!search) return text
    const idx = text.toLowerCase().indexOf(searchLower)
    if (idx === -1) return text
    return (
      <>
        {text.slice(0, idx)}
        <mark className="bg-yellow-300 text-gray-900 rounded-sm">{text.slice(idx, idx + search.length)}</mark>
        {text.slice(idx + search.length)}
      </>
    )
  }

  return (
    <Layout>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 text-sm text-gray-400 dark:text-neutral-500 mb-4 flex-wrap">
        <Link to={`/clusters/${clusterId}/namespaces/${ns}/pods`} className="hover:text-gray-700 dark:hover:text-neutral-300 transition-colors">
          {ns}
        </Link>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <Link to={`/clusters/${clusterId}/namespaces/${ns}/pods`} className="hover:text-gray-700 dark:hover:text-neutral-300 transition-colors">
          {pod}
        </Link>
        <IconChevronRight className="w-3.5 h-3.5 shrink-0" />
        <span className="text-neutral-600 dark:text-neutral-400 font-medium">Logs</span>
      </nav>

      {/* Terminal card — deliberately always-dark chrome, matches EditYamlButton/DiffViewer's editor styling */}
      <div className="rounded-xl border border-gray-200 dark:border-neutral-800 bg-neutral-950 overflow-hidden shadow-lg flex flex-col"
           style={{ height: 'calc(100vh - 11rem)' }}>

        {/* Toolbar */}
        <div className="flex flex-wrap items-center gap-2 px-4 py-2.5 border-b border-neutral-800 bg-neutral-900 shrink-0">
          {/* Container selector */}
          {podData && podData.containers.length > 1 && (
            <select
              value={containerName}
              onChange={handleContainerChange}
              className="h-7 rounded-md border border-neutral-700 bg-neutral-800 px-2 text-xs text-neutral-200
                         focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {podData.containers.map((c) => (
                <option key={c.name} value={c.name}>{c.name}</option>
              ))}
            </select>
          )}

          {podData?.containers.length === 1 && (
            <span className="text-xs text-gray-400 font-mono">{containerName}</span>
          )}

          <div className="ml-auto flex items-center gap-2 flex-wrap">
            {/* Search */}
            <div className="relative">
              <IconSearch className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-neutral-500" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search logs…"
                className="h-7 w-40 sm:w-56 rounded-md border border-neutral-700 bg-neutral-800 pl-7 pr-2 text-xs
                           text-neutral-200 placeholder-neutral-600 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              {search && (
                <button
                  onClick={() => setSearch('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-neutral-500 hover:text-neutral-300"
                >
                  <IconX className="w-3 h-3" />
                </button>
              )}
            </div>

            {/* Clear */}
            <button
              onClick={clearLogs}
              className="h-7 px-2.5 rounded-md border border-neutral-700 text-xs text-gray-400
                         hover:bg-neutral-800 hover:text-neutral-200 transition-colors"
            >
              Clear
            </button>

            {/* Reconnect */}
            <button
              onClick={reconnect}
              className="h-7 px-2.5 rounded-md border border-neutral-700 text-xs text-gray-400
                         hover:bg-neutral-800 hover:text-neutral-200 transition-colors flex items-center gap-1"
            >
              <IconRefresh className="w-3 h-3" />
              Reconnect
            </button>

            {/* Follow toggle */}
            <button
              onClick={() => (follow ? setFollow(false) : scrollToBottom())}
              className={`h-7 px-2.5 rounded-md border text-xs font-medium transition-colors
                          ${follow
                            ? 'border-blue-500 bg-blue-600 text-white hover:bg-blue-700'
                            : 'border-neutral-700 text-gray-400 hover:bg-neutral-800 hover:text-neutral-200'}`}
            >
              Follow
            </button>
          </div>
        </div>

        {/* Log output */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex-1 overflow-y-auto font-mono text-xs leading-5 text-neutral-300 px-4 py-3"
        >
          {filtered.length === 0 && connState === 'connected' && (
            <p className="text-neutral-600 italic">{search ? 'No matching lines.' : 'No output yet.'}</p>
          )}
          {filtered.map((line, idx) => (
            <div
              key={line.id}
              className={`flex gap-3 hover:bg-neutral-900 -mx-4 px-4 py-px rounded
                          ${search && line.text.toLowerCase().includes(searchLower) ? 'bg-yellow-950/40' : ''}`}
            >
              <span className="select-none text-neutral-600 w-10 shrink-0 text-right">
                {idx + 1}
              </span>
              <span className="break-all whitespace-pre-wrap min-w-0">{highlight(line.text)}</span>
            </div>
          ))}
        </div>

        {/* Status bar */}
        <div className="flex items-center justify-between px-4 py-1.5 border-t border-neutral-800 bg-neutral-900 shrink-0">
          <div className="flex items-center gap-2">
            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${
              connState === 'connected' ? 'bg-emerald-400' :
              connState === 'connecting' ? 'bg-amber-400 animate-pulse' :
              connState === 'error' ? 'bg-red-400' : 'bg-gray-500'
            }`} />
            <span className="text-[10px] text-neutral-500 uppercase tracking-wide">
              {connState === 'connected' ? 'Live' :
               connState === 'connecting' ? 'Connecting…' :
               connState === 'error' ? `Error${errorMsg ? ': ' + errorMsg : ''}` : 'Closed'}
            </span>
          </div>
          <div className="flex items-center gap-3 text-[10px] text-neutral-600">
            {search && <span>{filtered.length} match{filtered.length !== 1 ? 'es' : ''}</span>}
            <span>{lines.length.toLocaleString()} line{lines.length !== 1 ? 's' : ''}</span>
          </div>
        </div>
      </div>

      {/* Scroll-to-bottom FAB (visible when not following) */}
      {!follow && (
        <button
          onClick={scrollToBottom}
          className="fixed bottom-6 right-20 flex items-center gap-1.5 px-3 py-2 rounded-full
                     bg-blue-600 text-white text-xs font-medium shadow-lg hover:bg-blue-700 transition-colors z-10"
        >
          <IconArrowDown className="w-3.5 h-3.5" />
          Follow
        </button>
      )}
    </Layout>
  )
}
