/** Reads Spring's readable CSRF cookie so streaming fetch() calls pass CSRF checks. */
function csrfToken(): string {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return match ? decodeURIComponent(match.split('=')[1]) : ''
}

/** Carries the status so a caller can tell "the thing is gone" (404) apart from
 *  a generic failure and recover, instead of only having a message to show. */
export class StreamHttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'StreamHttpError'
  }
}

interface StreamOptions {
  signal?: AbortSignal
  /** Called once the response headers arrive, before the first chunk. Chat uses
   *  it to learn the id of a conversation the server just opened, which only
   *  the headers can carry — the body is the answer itself. */
  onResponse?: (res: Response) => void
}

/**
 * POSTs JSON and streams the plain-text response chunk by chunk into `onChunk`.
 * Used by every AI streaming endpoint (chat, manifest draft, manifest analyze).
 */
export async function streamText(
  url: string,
  body: unknown,
  onChunk: (chunk: string) => void,
  options: StreamOptions = {},
): Promise<void> {
  const { signal, onResponse } = options
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    throw new StreamHttpError(
      res.status,
      res.status === 403 ? 'Not authorized' : `Request failed (${res.status})`,
    )
  }
  onResponse?.(res)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    onChunk(decoder.decode(value, { stream: true }))
  }
}
