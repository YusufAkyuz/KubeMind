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
      theme: { background: '#0a0a0a', foreground: '#e5e5e5' },
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

    // Window resizes are one trigger, but the terminal's own container can also
    // change size without the window changing at all — dragging the docked panel's
    // resize handle, minimizing/restoring it, or switching tabs (display:none -> block).
    // A ResizeObserver on the container itself catches all of these; a plain window
    // 'resize' listener alone was missing them, which left stale/cut-off content.
    const resizeObserver = new ResizeObserver(() => fit.fit())
    resizeObserver.observe(termElRef.current)

    return () => {
      resizeObserver.disconnect()
      dataDisposable.dispose()
      resizeDisposable.dispose()
      ws.close()
      term.dispose()
    }
  }, [wsUrl, enabled])

  return { termElRef, connState }
}
