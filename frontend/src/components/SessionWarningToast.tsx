import { IconAlertTriangle, IconX } from './Icons'

interface Props {
  tone: 'amber' | 'red'
  message: string
  onConnect: () => void
  onDismiss: () => void
}

const TONE = {
  amber: { box: 'border-amber-200 bg-amber-50 text-amber-900', button: 'bg-amber-600 hover:bg-amber-700' },
  red: { box: 'border-red-200 bg-red-50 text-red-900', button: 'bg-red-600 hover:bg-red-700' },
}

/**
 * A dismissible, non-auto-expiring warning — toast-styled so it doesn't
 * dominate the page, but stays until the user acts (unlike the regular
 * success/error Toast, which times out on its own).
 */
export function SessionWarningToast({ tone, message, onConnect, onDismiss }: Props) {
  const t = TONE[tone]
  return (
    <div className={`fixed top-5 right-5 z-40 flex items-start gap-2.5 w-full max-w-sm rounded-lg border ${t.box} shadow-lg px-4 py-3`}>
      <IconAlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
      <p className="flex-1 text-sm font-medium leading-snug">{message}</p>
      <div className="flex items-center gap-1.5 shrink-0">
        <button
          onClick={onConnect}
          className={`rounded-md px-2.5 py-1 text-xs font-medium text-white transition-colors ${t.button}`}
        >
          Connect
        </button>
        <button onClick={onDismiss} className="p-1 opacity-60 hover:opacity-100 transition-opacity" aria-label="Dismiss">
          <IconX className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}
