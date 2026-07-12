/** crypto.randomUUID() only exists in secure contexts (https:// or localhost) —
 *  falls back to a non-cryptographic v4-shaped id when accessed over plain HTTP
 *  on a non-localhost host (e.g. a bare NodePort/IP during on-prem testing). */
export function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
