import { useEffect, useRef, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'

export type TerminalConnState = 'connecting' | 'connected' | 'closed'

/**
 * Wires an xterm.js terminal to a WebSocket exec bridge. Shared by pod exec,
 * node exec, and the cluster terminal — the only difference between them is
 * the URL. Pass `enabled: false` to defer connecting (e.g. until the user
 * confirms a privileged session).
 */
export function useWsTerminal(wsUrl: string | null, enabled: boolean) {
  const [connState, setConnState] = useState<TerminalConnState>('connecting')
  const termElRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!enabled || !wsUrl || !termElRef.current) return

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

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}${wsUrl}`)

    const sendResize = () => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }))
      }
    }

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

    const dataDisposable = term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'stdin', data }))
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
  }, [wsUrl, enabled])

  return { termElRef, connState }
}
