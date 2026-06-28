/** Returns a human-readable age string from an ISO timestamp, e.g. "3h", "2d", "45m". */
export function formatAge(timestamp: string | null | undefined): string {
  if (!timestamp) return '—'
  const ms = Date.now() - new Date(timestamp).getTime()
  if (ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}
